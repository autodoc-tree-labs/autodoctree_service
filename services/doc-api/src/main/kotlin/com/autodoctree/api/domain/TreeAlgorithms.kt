package com.autodoctree.api.domain

import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.EmbeddingRow
import com.autodoctree.api.db.FeedbackEventRow
import com.autodoctree.api.db.TreeNodeRow
import com.autodoctree.api.reranker.PairRerankerClient
import com.autodoctree.api.reranker.RerankerPairInput
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

data class NeighborLink(
    val documentId: String,
    val similarity: Double,
    val semanticSimilarity: Double? = null,
    val lexicalSimilarity: Double = 0.0,
    val lexicalGatePassed: Boolean = false,
    val sharedEntityCount: Int = 0,
    val titleOverlap: Int = 0,
    val reason: String = "UNKNOWN"
)

data class NeighborGraph(
    val adjacency: Map<String, List<NeighborLink>>,
    val stats: NeighborBuildStats = NeighborBuildStats()
)

data class NeighborBuildStats(
    val edgeCount: Int = 0,
    val filteredEdgeCount: Int = 0,
    val averageSimilarity: Double = 0.0,
    val mutualPassRate: Double = 1.0,
    val snnPassRate: Double = 1.0,
    val hubDocCount: Int = 0,
    val rerankerValidatedPairs: Int = 0,
    val rerankerPassRate: Double = 1.0,
    val rerankerFallbackRate: Double = 0.0,
    val similaritySourceBreakdown: SimilaritySourceBreakdown = SimilaritySourceBreakdown(),
    val similarityDistributions: SimilarityDistributions = SimilarityDistributions(),
    val edgeFilterStats: EdgeFilterStats = EdgeFilterStats(),
    val degreeStats: DegreeStats = DegreeStats(),
    val reasonBreakdown: Map<String, Int> = emptyMap(),
    val minSimilarityDecision: MinSimilarityDecision = MinSimilarityDecision()
)

data class SimilaritySourceBreakdown(
    val embeddingOnly: Int = 0,
    val lexicalOnly: Int = 0,
    val fused: Int = 0
)

data class SimilarityDistributions(
    val semantic: DistributionStats = DistributionStats(),
    val lexical: DistributionStats = DistributionStats(),
    val fused: DistributionStats = DistributionStats(),
    val semanticScale: String = "normalized_cosine"
)

data class DistributionStats(
    val count: Long = 0,
    val sampledCount: Int = 0,
    val mean: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val p50: Double = 0.0,
    val p90: Double = 0.0,
    val p95: Double = 0.0,
    val p99: Double = 0.0
)

data class EdgeFilterStats(
    val evaluatedPairs: Int = 0,
    val edgesBeforeFilter: Int = 0,
    val edgesFilteredByMinSimilarity: Int = 0,
    val edgesAfterTopK: Int = 0,
    val edgesFilteredByMutualKnn: Int = 0,
    val edgesFilteredBySnn: Int = 0,
    val edgesFilteredByDegreeCap: Int = 0,
    val edgesAfterAllFilters: Int = 0
)

data class DegreeStats(
    val mean: Double = 0.0,
    val p95: Double = 0.0,
    val p99: Double = 0.0,
    val max: Int = 0,
    val threshold: Int = 10,
    val nodesAtOrAboveThreshold: Int = 0
)

data class MinSimilarityDecision(
    val configuredThreshold: Double = 0.0,
    val autoEnabled: Boolean = false,
    val autoBaselineP95: Double? = null,
    val autoThreshold: Double? = null,
    val effectiveThreshold: Double = 0.0
)

data class TreeCluster(
    val id: String,
    val documentIds: List<String>,
    val qualityScore: Double = 1.0
)

data class ClusterBuildStats(
    val clusterCount: Int = 0,
    val mergeAttempted: Int = 0,
    val merged: Int = 0,
    val keptSingleton: Int = 0,
    val splitOversizedAttempted: Int = 0,
    val splitRetryAttempted: Int = 0,
    val splitRetrySucceeded: Int = 0,
    val splitFallbackUsed: Int = 0
)

data class ClusterBuildResult(
    val clusters: List<TreeCluster>,
    val stats: ClusterBuildStats = ClusterBuildStats()
)

data class PersonalizationModel(
    private val docLabelScores: Map<String, Map<String, Double>>,
    private val keywordLabelScores: Map<String, Map<String, Double>>,
    private val entityLabelScores: Map<String, Map<String, Double>>,
    private val minScore: Double
) {
    fun preferredLabelFor(document: DocumentRow, tokenizer: (String) -> List<String>): String? {
        val explicit = topLabel(docLabelScores[document.id].orEmpty(), minScore)
        if (explicit != null) {
            return explicit
        }

        val aggregated = mutableMapOf<String, Double>()
        val tokens = tokenizer(document.title + " " + (document.bodyText ?: "")).take(16)
        tokens.forEach { token ->
            keywordLabelScores[token].orEmpty().forEach { (label, score) ->
                aggregated[label] = (aggregated[label] ?: 0.0) + score
            }
        }
        extractEntityTokens(document.title + " " + (document.bodyText ?: ""))
            .take(16)
            .forEach { entity ->
                entityLabelScores[entity].orEmpty().forEach { (label, score) ->
                    aggregated[label] = (aggregated[label] ?: 0.0) + score
                }
            }
        return topLabel(aggregated, minScore)
    }

    fun hasSignalFor(documentId: String): Boolean {
        return docLabelScores.containsKey(documentId)
    }

    private fun topLabel(scores: Map<String, Double>, threshold: Double): String? {
        if (scores.isEmpty()) {
            return null
        }
        val top = scores.maxByOrNull { it.value } ?: return null
        return if (top.value >= threshold) top.key else null
    }

    private fun extractEntityTokens(text: String): List<String> {
        return text
            .split(Regex("[^\\p{L}\\p{N}_-]+"))
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length >= 3 }
            .distinct()
    }
}

@Service
class NeighborBuilder(
    private val objectMapper: ObjectMapper,
    private val treeLabeler: TreeLabeler,
    meterRegistry: MeterRegistry,
    private val pairRerankerClient: PairRerankerClient? = null
) {
    private val durationTimer = meterRegistry.timer("tree.neighbor_builder.duration")
    private val docsSummary = meterRegistry.summary("tree.neighbor_builder.docs")
    private val edgesSummary = meterRegistry.summary("neighbor_edges_total")
    private val edgesTotalSummary = meterRegistry.summary("edges_total")
    private val edgesFilteredSummary = meterRegistry.summary("edges_filtered_total")
    private val legacyEdgesFilteredSummary = meterRegistry.summary("neighbor_edges_filtered_total")
    private val averageSimilaritySummary = meterRegistry.summary("tree.neighbor_builder.avg_similarity")
    private val averageSimilarityLegacySummary = meterRegistry.summary("avg_similarity")
    private val averageSemanticSummary = meterRegistry.summary("tree.neighbor_builder.avg_sem_similarity")
    private val averageLexicalSummary = meterRegistry.summary("tree.neighbor_builder.avg_lex_similarity")
    private val lexicalTokenCountSummary = meterRegistry.summary("tree.neighbor_builder.lexical_token_count")
    private val lexicalGatePassRateSummary = meterRegistry.summary("tree.neighbor_builder.lexical_gate_pass_rate")
    private val tfidfComputeTimer = meterRegistry.timer("tree.neighbor_builder.tfidf_compute")
    private val mutualPassRateSummary = meterRegistry.summary("tree.neighbor_builder.mutual_pass_rate")
    private val snnPassRateSummary = meterRegistry.summary("tree.neighbor_builder.snn_pass_rate")
    private val hubDocCountSummary = meterRegistry.summary("tree.neighbor_builder.hub_doc_count")
    private val edgeCreatedCounterEmbeddingOnly =
        meterRegistry.counter("edge_created_total", "reason", "EMBEDDING_ONLY")
    private val edgeCreatedCounterFusion =
        meterRegistry.counter("edge_created_total", "reason", "EMBEDDING_LEXICAL_GATED")
    private val edgeCreatedCounterLexical = meterRegistry.counter("edge_created_total", "reason", "LEXICAL_ONLY")
    private val rerankerValidatedPairsSummary = meterRegistry.summary("reranker_validated_pairs")
    private val rerankerPassRateSummary = meterRegistry.summary("reranker_pass_rate")
    private val rerankerFallbackCounter = meterRegistry.counter("reranker_fallback_total")

    fun build(
        workspaceId: String,
        documents: List<DocumentRow>,
        embeddings: Map<String, EmbeddingRow>,
        topK: Int,
        minSimilarity: Double = 0.0,
        normalize: Boolean = true,
        semanticWeight: Double = 0.8,
        lexicalWeight: Double = 0.2,
        lexicalGate: Double = 0.35,
        mutualKnnRequired: Boolean = true,
        sharedNeighborJaccardMin: Double = 0.0,
        edgeBudget: Int = topK,
        degreeCap: Int = 10,
        bridgePrunePolicy: String = "LOWEST_SIM_FIRST",
        minSimilarityAuto: Boolean = false,
        minSimilarityAutoMargin: Double = 0.05,
        rerankerEnabled: Boolean = false,
        rerankerPerDocBudget: Int = 4,
        rerankerPassThreshold: Double = 0.55,
        rerankerTextByDocumentId: Map<String, String> = emptyMap()
    ): NeighborGraph {
        val sample = Timer.start()
        val useEmbeddingSimilarity = embeddings.values.any { !it.modelVersion.startsWith("local-stub", ignoreCase = true) }
        val effectiveTopK = topK.coerceAtLeast(1)
        val effectiveEdgeBudget = edgeBudget.coerceAtLeast(1)
        val directionalTopK = minOf(effectiveTopK, effectiveEdgeBudget).coerceAtLeast(1)
        val effectiveSnnThreshold = sharedNeighborJaccardMin.coerceIn(0.0, 1.0)
        val configuredSimilarityThreshold = minSimilarity.coerceIn(-1.0, 1.0)
        val effectiveDegreeCap = degreeCap.coerceAtLeast(1)
        val normalizedBridgePolicy = bridgePrunePolicy.trim().uppercase(Locale.ROOT).ifBlank { "LOWEST_SIM_FIRST" }
        val semanticSampler = ReservoirDistributionSampler(seed = 20260219L)
        val lexicalSampler = ReservoirDistributionSampler(seed = 20260220L)
        val fusedSampler = ReservoirDistributionSampler(seed = 20260221L)
        val vectorByDoc = documents.associate { document ->
            val vector = embeddings[document.id]?.let { embedding ->
                objectMapper.readValue(embedding.vectorJson, List::class.java)
                    .mapNotNull { number -> (number as? Number)?.toDouble() }
            }
            document.id to vector
        }
        val lexicalModel = buildLexicalModel(documents)
        val lexicalVectors = lexicalModel.tfidfVectors
        val lexicalTokenSets = lexicalModel.tokenSets
        val lexicalTermFrequencies = lexicalModel.termFrequencies
        val lexicalTokens = lexicalModel.tokens
        lexicalTokens.values.forEach { tokens ->
            lexicalTokenCountSummary.record(tokens.size.toDouble())
        }
        val titleTokenSets = documents.associate { document ->
            document.id to treeLabeler.tokenize(document.title).filterNot { it.contains('-') }.toSet()
        }
        val entityTokenSets = documents.associate { document ->
            document.id to extractEntityTokens(document.title + " " + (document.bodyText ?: "")).toSet()
        }

        data class PairSimilarity(
            val similarity: Double,
            val semanticSimilarity: Double?,
            val lexicalSimilarity: Double,
            val lexicalGatePassed: Boolean,
            val sharedEntityCount: Int,
            val titleOverlap: Int,
            val reason: String
        )

        val docById = documents.associateBy { it.id }

        fun computePairSimilarity(sourceId: String, candidateId: String): PairSimilarity {
            val sourceDoc = docById[sourceId] ?: error("source doc missing")
            val candidateDoc = docById[candidateId] ?: error("candidate doc missing")
            val source = vectorByDoc[sourceId].orEmpty()
            val candidateVector = vectorByDoc[candidateId].orEmpty()
            val sourceLexical = lexicalVectors[sourceId].orEmpty()
            val candidateLexical = lexicalVectors[candidateId].orEmpty()
            val sourceTokens = lexicalTokens[sourceId].orEmpty()
            val candidateTokens = lexicalTokens[candidateId].orEmpty()
            val tokenOverlapScore = overlapCoefficient(
                lexicalTokenSets[sourceId].orEmpty(),
                lexicalTokenSets[candidateId].orEmpty()
            )
            val bm25Forward = bm25LiteScore(
                queryTokens = sourceTokens,
                candidateTermFreq = lexicalTermFrequencies[candidateId].orEmpty(),
                candidateLength = candidateTokens.size,
                avgDocLength = lexicalModel.avgDocLength,
                idf = lexicalModel.idf
            )
            val bm25Reverse = bm25LiteScore(
                queryTokens = candidateTokens,
                candidateTermFreq = lexicalTermFrequencies[sourceId].orEmpty(),
                candidateLength = sourceTokens.size,
                avgDocLength = lexicalModel.avgDocLength,
                idf = lexicalModel.idf
            )
            val bm25Similarity = normalizeBm25Lite((bm25Forward + bm25Reverse) / 2.0)
            val tfidfSimilarity = cosineSparse(sourceLexical, candidateLexical).coerceIn(0.0, 1.0)
            val lexicalSimilarity = (
                (tfidfSimilarity * 0.30) +
                    (tokenOverlapScore * 0.35) +
                    (bm25Similarity * 0.35)
                ).coerceIn(0.0, 1.0)
            val titleOverlap = titleTokenSets[sourceDoc.id].orEmpty().intersect(
                titleTokenSets[candidateDoc.id].orEmpty()
            ).size
            val sharedEntities = entityTokenSets[sourceDoc.id].orEmpty().intersect(
                entityTokenSets[candidateDoc.id].orEmpty()
            ).size
            val embeddingSimilarity = if (useEmbeddingSimilarity && source.isNotEmpty() && candidateVector.isNotEmpty()) {
                cosine(source, candidateVector).let { raw ->
                    if (normalize) normalizeCosine(raw) else raw
                }
            } else {
                null
            }
            val lexicalConsensus = ((tokenOverlapScore + bm25Similarity) / 2.0).coerceIn(0.0, 1.0)
            val lexicalGatePassed = lexicalConsensus >= lexicalGate
            val similarity = if (embeddingSimilarity != null) {
                val semW = semanticWeight.coerceAtLeast(0.0)
                val lexW = lexicalWeight.coerceAtLeast(0.0)
                val effectiveLexW = if (lexicalGatePassed) lexW else 0.0
                val denominator = (semW + effectiveLexW).coerceAtLeast(0.000001)
                ((embeddingSimilarity * semW) + (lexicalSimilarity * effectiveLexW)) / denominator
            } else {
                lexicalSimilarity
            }
            return PairSimilarity(
                similarity = similarity.coerceIn(-1.0, 1.0),
                semanticSimilarity = embeddingSimilarity,
                lexicalSimilarity = lexicalSimilarity,
                lexicalGatePassed = lexicalGatePassed,
                sharedEntityCount = sharedEntities,
                titleOverlap = titleOverlap,
                reason = when {
                    embeddingSimilarity == null -> "LEXICAL_ONLY"
                    lexicalGatePassed -> "EMBEDDING_LEXICAL_GATED"
                    else -> "EMBEDDING_ONLY"
                }
            )
        }

        var evaluatedPairs = 0
        documents.forEach { source ->
            val sourceVector = vectorByDoc[source.id].orEmpty()
            val sourceLexical = lexicalVectors[source.id].orEmpty()
            if (sourceVector.isEmpty() && !useEmbeddingSimilarity && sourceLexical.isEmpty()) {
                return@forEach
            }
            documents.asSequence()
                .filter { it.id != source.id }
                .forEach { candidate ->
                    evaluatedPairs += 1
                    val pair = computePairSimilarity(source.id, candidate.id)
                    pair.semanticSimilarity?.let { semanticSampler.add(it) }
                    lexicalSampler.add(pair.lexicalSimilarity)
                    fusedSampler.add(pair.similarity)
                }
        }

        val fusedDistribution = fusedSampler.stats()
        val autoBaseline = if (minSimilarityAuto && fusedDistribution.count > 0) {
            fusedDistribution.p95
        } else {
            null
        }
        val autoThreshold = autoBaseline?.let { (it + minSimilarityAutoMargin).coerceIn(-1.0, 1.0) }
        val effectiveSimilarityThreshold = autoThreshold?.let { maxOf(configuredSimilarityThreshold, it) }
            ?: configuredSimilarityThreshold
        val minSimilarityDecision = MinSimilarityDecision(
            configuredThreshold = configuredSimilarityThreshold,
            autoEnabled = minSimilarityAuto,
            autoBaselineP95 = autoBaseline,
            autoThreshold = autoThreshold,
            effectiveThreshold = effectiveSimilarityThreshold
        )

        val minFilteredAdjacency = mutableMapOf<String, MutableList<NeighborLink>>()
        var filteredByMinSimilarity = 0

        documents.forEach { document ->
            val source = vectorByDoc[document.id].orEmpty()
            val sourceLexical = lexicalVectors[document.id].orEmpty()
            if (source.isEmpty() && !useEmbeddingSimilarity && sourceLexical.isEmpty()) {
                minFilteredAdjacency[document.id] = mutableListOf()
                return@forEach
            }

            val neighbors = documents
                .asSequence()
                .filter { it.id != document.id }
                .mapNotNull { candidate ->
                    val pair = computePairSimilarity(document.id, candidate.id)
                    if (pair.similarity < effectiveSimilarityThreshold) {
                        filteredByMinSimilarity += 1
                        return@mapNotNull null
                    }
                    NeighborLink(
                        documentId = candidate.id,
                        similarity = pair.similarity,
                        semanticSimilarity = pair.semanticSimilarity,
                        lexicalSimilarity = pair.lexicalSimilarity,
                        lexicalGatePassed = pair.lexicalGatePassed,
                        sharedEntityCount = pair.sharedEntityCount,
                        titleOverlap = pair.titleOverlap,
                        reason = pair.reason
                    )
                }
                .sortedByDescending { it.similarity }
                .distinctBy { it.documentId }
                .toMutableList()

            minFilteredAdjacency[document.id] = neighbors
        }

        val topKAdjacency = minFilteredAdjacency.mapValues { (_, neighbors) ->
            neighbors
                .sortedByDescending { it.similarity }
                .take(directionalTopK)
                .toMutableList()
        }
        val edgesAfterTopK = topKAdjacency.values.sumOf { it.size }
        val topNeighborSets = topKAdjacency.mapValues { (_, neighbors) ->
            neighbors.mapTo(mutableSetOf()) { it.documentId }
        }

        var mutualEvaluated = 0
        var mutualPassed = 0
        var edgesFilteredByMutual = 0
        var snnEvaluated = 0
        var snnPassed = 0
        var edgesFilteredBySnn = 0
        val mutualSnnAdjacency = mutableMapOf<String, MutableList<NeighborLink>>()
        val snnEnabled = effectiveSnnThreshold > 0.0

        topKAdjacency.forEach { (docId, candidates) ->
            val filtered = candidates.filter { link ->
                if (mutualKnnRequired) {
                    mutualEvaluated += 1
                    val reverseTop = topNeighborSets[link.documentId].orEmpty()
                    if (!reverseTop.contains(docId)) {
                        edgesFilteredByMutual += 1
                        return@filter false
                    }
                    mutualPassed += 1
                }

                if (snnEnabled) {
                    val left = topNeighborSets[docId].orEmpty()
                    val right = topNeighborSets[link.documentId].orEmpty()
                    val union = left.union(right)
                    val snn = if (union.isEmpty()) {
                        0.0
                    } else {
                        left.intersect(right).size.toDouble() / union.size.toDouble()
                    }
                    snnEvaluated += 1
                    if (snn >= effectiveSnnThreshold) {
                        snnPassed += 1
                    } else {
                        edgesFilteredBySnn += 1
                        return@filter false
                    }
                }
                true
            }.sortedByDescending { it.similarity }
            mutualSnnAdjacency[docId] = filtered.toMutableList()
        }

        val bridgePrunedAdjacency = applyDegreeCap(
            adjacency = mutualSnnAdjacency,
            allDocumentIds = documents.map { it.id },
            degreeCap = effectiveDegreeCap,
            policy = normalizedBridgePolicy
        )
        val edgesFilteredByDegreeCap = bridgePrunedAdjacency.filteredDirectionalEdges
        val adjacencyAfterEdgePolicy = bridgePrunedAdjacency.adjacency

        val degreeThreshold = 10
        val degreeStats = computeDegreeStats(
            allDocumentIds = documents.map { it.id },
            adjacency = adjacencyAfterEdgePolicy,
            threshold = degreeThreshold
        )

        var rerankerValidatedPairs = 0
        var rerankerPassRate = 1.0
        var rerankerFallbackRate = 0.0
        val finalAdjacency = adjacencyAfterEdgePolicy.mapValues { (_, links) -> links.toMutableList() }.toMutableMap()
        if (rerankerEnabled && pairRerankerClient != null) {
            val perDocBudget = rerankerPerDocBudget.coerceAtLeast(1)
            val scoreThreshold = rerankerPassThreshold.coerceIn(0.0, 1.0)
            val pairInputs = linkedMapOf<String, RerankerPairInput>()
            finalAdjacency.forEach { (leftDocId, links) ->
                val leftText = rerankerTextByDocumentId[leftDocId].orEmpty().trim()
                if (leftText.isBlank()) {
                    return@forEach
                }
                links.take(perDocBudget).forEach { link ->
                    val rightText = rerankerTextByDocumentId[link.documentId].orEmpty().trim()
                    if (rightText.isBlank()) {
                        return@forEach
                    }
                    val pairKey = pairKey(leftDocId, link.documentId)
                    pairInputs.putIfAbsent(
                        pairKey,
                        RerankerPairInput(
                            pairKey = pairKey,
                            leftText = leftText,
                            rightText = rightText
                        )
                    )
                }
            }
            if (pairInputs.isNotEmpty()) {
                try {
                    val pairScores = pairRerankerClient.scorePairs(workspaceId, pairInputs.values.toList())
                    rerankerValidatedPairs = pairInputs.size
                    val passByPair = pairInputs.keys.associateWith { pairKey ->
                        (pairScores[pairKey] ?: 0.0) >= scoreThreshold
                    }
                    val passedPairs = passByPair.values.count { it }
                    rerankerPassRate = if (rerankerValidatedPairs == 0) {
                        1.0
                    } else {
                        passedPairs.toDouble() / rerankerValidatedPairs.toDouble()
                    }
                    finalAdjacency.keys.forEach { docId ->
                        val reranked = finalAdjacency[docId].orEmpty()
                            .mapNotNull { link ->
                                val pairKey = pairKey(docId, link.documentId)
                                val keep = passByPair[pairKey] ?: true
                                if (!keep) {
                                    return@mapNotNull null
                                }
                                val rerankScore = pairScores[pairKey]?.coerceIn(0.0, 1.0)
                                if (rerankScore == null) {
                                    return@mapNotNull link
                                }
                                val blendedScore = ((link.similarity * 0.65) + (rerankScore * 0.35)).coerceIn(0.0, 1.0)
                                link.copy(
                                    similarity = blendedScore,
                                    reason = "${link.reason}_RERANKED"
                                )
                            }
                            .sortedByDescending { it.similarity }
                            .toMutableList()
                        finalAdjacency[docId] = reranked
                    }
                } catch (_: Exception) {
                    rerankerFallbackRate = 1.0
                    rerankerFallbackCounter.increment()
                }
            }
        }

        var totalSimilarity = 0.0
        var totalSemantic = 0.0
        var semanticCount = 0
        var totalLexical = 0.0
        var lexicalCount = 0
        var embeddingOnlyEdges = 0
        var lexicalOnlyEdges = 0
        var fusedEdges = 0
        var lexicalGateEvaluated = 0
        var lexicalGatePassedCount = 0

        finalAdjacency.values.forEach { links ->
            links.forEach { link ->
                totalSimilarity += link.similarity
                totalLexical += link.lexicalSimilarity
                lexicalCount += 1
                lexicalGateEvaluated += 1
                if (link.lexicalGatePassed) {
                    lexicalGatePassedCount += 1
                }
                if (link.semanticSimilarity != null) {
                    totalSemantic += link.semanticSimilarity
                    semanticCount += 1
                    if (link.lexicalGatePassed) {
                        edgeCreatedCounterFusion.increment()
                        fusedEdges += 1
                    } else {
                        edgeCreatedCounterEmbeddingOnly.increment()
                        embeddingOnlyEdges += 1
                    }
                } else {
                    edgeCreatedCounterLexical.increment()
                    lexicalOnlyEdges += 1
                }
            }
        }

        sample.stop(durationTimer)
        docsSummary.record(documents.size.toDouble())
        val edgeCount = finalAdjacency.values.sumOf { it.size }
        val edgesBeforeFilter = evaluatedPairs
        val filteredOutCount = filteredByMinSimilarity + (edgesAfterTopK - edgeCount).coerceAtLeast(0)
        val mutualPassRate = if (!mutualKnnRequired || mutualEvaluated == 0) {
            1.0
        } else {
            mutualPassed.toDouble() / mutualEvaluated.toDouble()
        }
        val snnPassRate = if (!snnEnabled || snnEvaluated == 0) {
            1.0
        } else {
            snnPassed.toDouble() / snnEvaluated.toDouble()
        }
        val hubDocCount = degreeStats.nodesAtOrAboveThreshold
        val lexicalGatePassRate = if (lexicalGateEvaluated == 0) {
            0.0
        } else {
            lexicalGatePassedCount.toDouble() / lexicalGateEvaluated.toDouble()
        }

        edgesSummary.record(edgeCount.toDouble())
        edgesTotalSummary.record(edgeCount.toDouble())
        edgesFilteredSummary.record(filteredOutCount.toDouble())
        legacyEdgesFilteredSummary.record(filteredOutCount.toDouble())
        lexicalGatePassRateSummary.record(lexicalGatePassRate)
        mutualPassRateSummary.record(mutualPassRate)
        snnPassRateSummary.record(snnPassRate)
        hubDocCountSummary.record(hubDocCount.toDouble())
        rerankerValidatedPairsSummary.record(rerankerValidatedPairs.toDouble())
        rerankerPassRateSummary.record(rerankerPassRate)
        var averageSimilarity = 0.0
        if (edgeCount > 0) {
            averageSimilarity = totalSimilarity / edgeCount.toDouble()
            averageSimilaritySummary.record(averageSimilarity)
            averageSimilarityLegacySummary.record(averageSimilarity)
        }
        if (semanticCount > 0) {
            averageSemanticSummary.record(totalSemantic / semanticCount.toDouble())
        }
        if (lexicalCount > 0) {
            averageLexicalSummary.record(totalLexical / lexicalCount.toDouble())
        }
        val reasonBreakdown = finalAdjacency.values
            .flatten()
            .groupingBy { normalizeReason(it.reason) }
            .eachCount()
        val similaritySourceBreakdown = SimilaritySourceBreakdown(
            embeddingOnly = reasonBreakdown["EMBEDDING_ONLY"] ?: 0,
            lexicalOnly = reasonBreakdown["LEXICAL_ONLY"] ?: 0,
            fused = reasonBreakdown["EMBEDDING_LEXICAL_GATED"] ?: 0
        )
        val edgeFilterStats = EdgeFilterStats(
            evaluatedPairs = evaluatedPairs,
            edgesBeforeFilter = edgesBeforeFilter,
            edgesFilteredByMinSimilarity = filteredByMinSimilarity,
            edgesAfterTopK = edgesAfterTopK,
            edgesFilteredByMutualKnn = edgesFilteredByMutual,
            edgesFilteredBySnn = edgesFilteredBySnn,
            edgesFilteredByDegreeCap = edgesFilteredByDegreeCap,
            edgesAfterAllFilters = edgeCount
        )
        val similarityDistributions = SimilarityDistributions(
            semantic = semanticSampler.stats(),
            lexical = lexicalSampler.stats(),
            fused = fusedDistribution,
            semanticScale = if (normalize) "normalized_cosine" else "raw_cosine"
        )

        return NeighborGraph(
            adjacency = finalAdjacency,
            stats = NeighborBuildStats(
                edgeCount = edgeCount,
                filteredEdgeCount = filteredOutCount,
                averageSimilarity = averageSimilarity,
                mutualPassRate = mutualPassRate,
                snnPassRate = snnPassRate,
                hubDocCount = hubDocCount,
                rerankerValidatedPairs = rerankerValidatedPairs,
                rerankerPassRate = rerankerPassRate,
                rerankerFallbackRate = rerankerFallbackRate,
                similaritySourceBreakdown = similaritySourceBreakdown,
                similarityDistributions = similarityDistributions,
                edgeFilterStats = edgeFilterStats,
                degreeStats = degreeStats,
                reasonBreakdown = reasonBreakdown,
                minSimilarityDecision = minSimilarityDecision
            )
        )
    }

    private data class DegreeCapResult(
        val adjacency: Map<String, MutableList<NeighborLink>>,
        val filteredDirectionalEdges: Int
    )

    private fun applyDegreeCap(
        adjacency: Map<String, MutableList<NeighborLink>>,
        allDocumentIds: List<String>,
        degreeCap: Int,
        policy: String
    ): DegreeCapResult {
        val beforeEdgeCount = adjacency.values.sumOf { it.size }
        val edgeWeightByPair = mutableMapOf<String, Double>()
        val pairNodes = mutableMapOf<String, Pair<String, String>>()
        val incidentPairsByNode = allDocumentIds.associateWith { mutableSetOf<String>() }.toMutableMap()
        adjacency.forEach { (docId, links) ->
            links.forEach { link ->
                val pairKey = pairKey(docId, link.documentId)
                val left = minOf(docId, link.documentId)
                val right = maxOf(docId, link.documentId)
                edgeWeightByPair[pairKey] = maxOf(edgeWeightByPair[pairKey] ?: 0.0, link.similarity)
                pairNodes[pairKey] = left to right
                incidentPairsByNode.getOrPut(left) { mutableSetOf() }.add(pairKey)
                incidentPairsByNode.getOrPut(right) { mutableSetOf() }.add(pairKey)
            }
        }
        if (degreeCap <= 0 || edgeWeightByPair.isEmpty()) {
            return DegreeCapResult(adjacency, 0)
        }
        val degreeByNode = allDocumentIds.associateWith { node ->
            incidentPairsByNode[node].orEmpty().size
        }.toMutableMap()
        val removedPairs = mutableSetOf<String>()
        while (true) {
            val overflowNode = degreeByNode.entries
                .filter { it.value > degreeCap }
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
                ?.key
                ?: break
            val candidatePairs = incidentPairsByNode[overflowNode].orEmpty()
                .filterNot { removedPairs.contains(it) }
            if (candidatePairs.isEmpty()) {
                break
            }
            val selectedPair = when (policy) {
                "LOWEST_SIM_FIRST" -> candidatePairs.minWithOrNull(
                    compareBy<String> { edgeWeightByPair[it] ?: 0.0 }
                        .thenBy { it }
                )

                else -> candidatePairs.minWithOrNull(
                    compareBy<String> { edgeWeightByPair[it] ?: 0.0 }
                        .thenBy { it }
                )
            } ?: break
            removedPairs += selectedPair
            val nodes = pairNodes[selectedPair] ?: continue
            degreeByNode[nodes.first] = (degreeByNode[nodes.first] ?: 0).coerceAtLeast(1) - 1
            degreeByNode[nodes.second] = (degreeByNode[nodes.second] ?: 0).coerceAtLeast(1) - 1
        }
        if (removedPairs.isEmpty()) {
            return DegreeCapResult(adjacency, 0)
        }
        val prunedAdjacency = adjacency.mapValues { (docId, links) ->
            links.filterNot { link ->
                removedPairs.contains(pairKey(docId, link.documentId))
            }.toMutableList()
        }
        val afterEdgeCount = prunedAdjacency.values.sumOf { it.size }
        return DegreeCapResult(
            adjacency = prunedAdjacency,
            filteredDirectionalEdges = (beforeEdgeCount - afterEdgeCount).coerceAtLeast(0)
        )
    }

    private fun computeDegreeStats(
        allDocumentIds: List<String>,
        adjacency: Map<String, MutableList<NeighborLink>>,
        threshold: Int
    ): DegreeStats {
        if (allDocumentIds.isEmpty()) {
            return DegreeStats(threshold = threshold)
        }
        val undirected = allDocumentIds.associateWith { mutableSetOf<String>() }.toMutableMap()
        adjacency.forEach { (docId, links) ->
            links.forEach { link ->
                undirected.getOrPut(docId) { mutableSetOf() }.add(link.documentId)
                undirected.getOrPut(link.documentId) { mutableSetOf() }.add(docId)
            }
        }
        val degrees = allDocumentIds.map { docId -> undirected[docId].orEmpty().size }.sorted()
        val mean = degrees.average().takeIf { !it.isNaN() } ?: 0.0
        val p95 = percentileInt(degrees, 0.95).toDouble()
        val p99 = percentileInt(degrees, 0.99).toDouble()
        val max = degrees.maxOrNull() ?: 0
        val nodesAtOrAboveThreshold = degrees.count { it >= threshold.coerceAtLeast(0) }
        return DegreeStats(
            mean = mean,
            p95 = p95,
            p99 = p99,
            max = max,
            threshold = threshold,
            nodesAtOrAboveThreshold = nodesAtOrAboveThreshold
        )
    }

    private fun percentileInt(sortedValues: List<Int>, quantile: Double): Int {
        if (sortedValues.isEmpty()) {
            return 0
        }
        val clamped = quantile.coerceIn(0.0, 1.0)
        val index = ((sortedValues.size - 1) * clamped).toInt().coerceIn(0, sortedValues.size - 1)
        return sortedValues[index]
    }

    private fun normalizeReason(raw: String): String {
        val upper = raw.trim().uppercase(Locale.ROOT)
        return when {
            upper.contains("EMBEDDING_LEXICAL_GATED") -> "EMBEDDING_LEXICAL_GATED"
            upper.contains("EMBEDDING_ONLY") -> "EMBEDDING_ONLY"
            upper.contains("LEXICAL_ONLY") -> "LEXICAL_ONLY"
            else -> upper.substringBefore("_RERANKED")
        }
    }

    private class ReservoirDistributionSampler(
        private val maxSamples: Int = 4096,
        seed: Long = 20260219L
    ) {
        private val random = Random(seed)
        private val samples = mutableListOf<Double>()
        private var seen: Long = 0
        private var sum = 0.0
        private var min = Double.POSITIVE_INFINITY
        private var max = Double.NEGATIVE_INFINITY

        fun add(value: Double) {
            if (!value.isFinite()) {
                return
            }
            seen += 1
            sum += value
            min = minOf(min, value)
            max = maxOf(max, value)
            if (samples.size < maxSamples) {
                samples += value
                return
            }
            val replaceIndex = random.nextLong(seen).toInt()
            if (replaceIndex < maxSamples) {
                samples[replaceIndex] = value
            }
        }

        fun stats(): DistributionStats {
            if (seen == 0L || samples.isEmpty()) {
                return DistributionStats()
            }
            val sorted = samples.sorted()
            return DistributionStats(
                count = seen,
                sampledCount = sorted.size,
                mean = sum / seen.toDouble(),
                min = min,
                max = max,
                p50 = percentile(sorted, 0.50),
                p90 = percentile(sorted, 0.90),
                p95 = percentile(sorted, 0.95),
                p99 = percentile(sorted, 0.99)
            )
        }

        private fun percentile(sorted: List<Double>, quantile: Double): Double {
            if (sorted.isEmpty()) {
                return 0.0
            }
            val clamped = quantile.coerceIn(0.0, 1.0)
            val index = ((sorted.size - 1) * clamped).toInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }
    }

    private fun pairKey(leftDocId: String, rightDocId: String): String {
        return if (leftDocId <= rightDocId) {
            "$leftDocId::$rightDocId"
        } else {
            "$rightDocId::$leftDocId"
        }
    }

    private data class LexicalModel(
        val tokens: Map<String, List<String>>,
        val tokenSets: Map<String, Set<String>>,
        val termFrequencies: Map<String, Map<String, Int>>,
        val tfidfVectors: Map<String, Map<String, Double>>,
        val idf: Map<String, Double>,
        val avgDocLength: Double
    )

    private fun buildLexicalModel(documents: List<DocumentRow>): LexicalModel {
        if (documents.isEmpty()) {
            return LexicalModel(
                tokens = emptyMap(),
                tokenSets = emptyMap(),
                termFrequencies = emptyMap(),
                tfidfVectors = emptyMap(),
                idf = emptyMap(),
                avgDocLength = 1.0
            )
        }

        val sample = Timer.start()
        try {
            val maxTokensPerDoc = 256
            val tokensByDoc = documents.associate { document ->
                val tokens = treeLabeler.tokenize(document.title + " " + (document.bodyText ?: ""))
                    .filterNot { it.contains('-') }
                    .take(maxTokensPerDoc)
                document.id to tokens
            }
            val tokenSetsByDoc = tokensByDoc.mapValues { (_, tokens) -> tokens.toSet() }
            val termFrequencies = tokensByDoc.mapValues { (_, tokens) ->
                tokens.groupingBy { it }.eachCount()
            }
            val docCount = documents.size.toDouble().coerceAtLeast(1.0)
            val df = mutableMapOf<String, Int>()
            tokenSetsByDoc.values.forEach { tokens ->
                tokens.forEach { token ->
                    df[token] = (df[token] ?: 0) + 1
                }
            }
            val idf = df.mapValues { (_, freq) ->
                ln((1.0 + docCount) / (1.0 + freq.toDouble())) + 1.0
            }
            val tfidfVectors = termFrequencies.mapValues { (_, tf) ->
                tf.mapValues { (token, freq) ->
                    freq.toDouble() * (idf[token] ?: 1.0)
                }
            }
            val avgDocLength = tokensByDoc.values
                .map { it.size.toDouble() }
                .average()
                .takeIf { !it.isNaN() && it > 0.0 }
                ?: 1.0
            return LexicalModel(
                tokens = tokensByDoc,
                tokenSets = tokenSetsByDoc,
                termFrequencies = termFrequencies,
                tfidfVectors = tfidfVectors,
                idf = idf,
                avgDocLength = avgDocLength
            )
        } finally {
            sample.stop(tfidfComputeTimer)
        }
    }

    private fun overlapCoefficient(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0
        }
        val overlap = left.intersect(right).size.toDouble()
        val denominator = minOf(left.size, right.size).toDouble().coerceAtLeast(1.0)
        return (overlap / denominator).coerceIn(0.0, 1.0)
    }

    private fun bm25LiteScore(
        queryTokens: List<String>,
        candidateTermFreq: Map<String, Int>,
        candidateLength: Int,
        avgDocLength: Double,
        idf: Map<String, Double>
    ): Double {
        if (queryTokens.isEmpty() || candidateTermFreq.isEmpty()) {
            return 0.0
        }
        val k1 = 1.2
        val b = 0.75
        val normalizedLength = candidateLength.toDouble().coerceAtLeast(1.0) / avgDocLength.coerceAtLeast(1.0)
        val lengthFactor = k1 * (1.0 - b + (b * normalizedLength))
        var total = 0.0
        queryTokens.distinct().forEach { token ->
            val tf = candidateTermFreq[token] ?: return@forEach
            val termIdf = idf[token] ?: return@forEach
            total += termIdf * ((tf.toDouble() * (k1 + 1.0)) / (tf.toDouble() + lengthFactor))
        }
        return total
    }

    private fun normalizeBm25Lite(value: Double): Double {
        if (value <= 0.0) {
            return 0.0
        }
        return (1.0 - exp(-value / 6.0)).coerceIn(0.0, 1.0)
    }

    private fun extractEntityTokens(text: String): List<String> {
        return text
            .split(Regex("[^\\p{L}\\p{N}_-]+"))
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length >= 3 }
            .distinct()
            .take(64)
    }

    private fun normalizeCosine(value: Double): Double {
        return ((value + 1.0) / 2.0).coerceIn(0.0, 1.0)
    }

    private fun cosineSparse(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0
        }

        val intersection = if (a.size <= b.size) a.keys else b.keys
        var dot = 0.0
        intersection.forEach { key ->
            val av = a[key] ?: return@forEach
            val bv = b[key] ?: return@forEach
            dot += av * bv
        }

        var an = 0.0
        a.values.forEach { value ->
            an += value * value
        }
        var bn = 0.0
        b.values.forEach { value ->
            bn += value * value
        }
        if (an == 0.0 || bn == 0.0) {
            return 0.0
        }
        return dot / (sqrt(an) * sqrt(bn))
    }

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0
        var dot = 0.0
        var an = 0.0
        var bn = 0.0
        for (i in 0 until size) {
            dot += a[i] * b[i]
            an += a[i] * a[i]
            bn += b[i] * b[i]
        }
        if (an == 0.0 || bn == 0.0) return 0.0
        return dot / (sqrt(an) * sqrt(bn))
    }
}

@Service
class TreeClusterer(
    private val treeProperties: TreeProperties,
    private val featureFlags: com.autodoctree.api.config.FeatureFlags,
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val durationTimer = meterRegistry.timer("tree.clusterer.duration")
    private val docsSummary = meterRegistry.summary("tree.clusterer.docs")
    private val clustersSummary = meterRegistry.summary("tree.clusterer.clusters")
    private val clusterCountSummary = meterRegistry.summary("cluster_count")
    private val clusterAverageSizeSummary = meterRegistry.summary("avg_cluster_size")
    private val modularityProxySummary = meterRegistry.summary("modularity_proxy")
    private val splitClusterCounter = meterRegistry.counter("split_cluster_total")
    private val consensusStrengthSummary = meterRegistry.summary("consensus_strength")
    private val unstableClusterCountSummary = meterRegistry.summary("unstable_cluster_count")

    private data class ConsensusResult(
        val communities: List<List<String>>,
        val consensusStrength: Double,
        val unstableClusterCount: Int,
        val strongEdgeCount: Int
    )

    private data class MergeSmallResult(
        val clusters: List<List<String>>,
        val mergeAttempted: Int,
        val merged: Int,
        val keptSingleton: Int
    )

    private data class SplitComponentResult(
        val components: List<List<String>>,
        val retryAttempted: Boolean = false,
        val retrySucceeded: Boolean = false,
        val fallbackUsed: Boolean = false
    )

    fun cluster(documents: List<DocumentRow>, graph: NeighborGraph, maxClusterSize: Int): List<TreeCluster> {
        return clusterWithStats(
            documents = documents,
            graph = graph,
            maxClusterSize = maxClusterSize
        ).clusters
    }

    fun clusterWithStats(documents: List<DocumentRow>, graph: NeighborGraph, maxClusterSize: Int): ClusterBuildResult {
        val sample = Timer.start()
        if (documents.isEmpty()) {
            return ClusterBuildResult(emptyList(), ClusterBuildStats())
        }

        val ids = documents.map { it.id }
        val undirected = ids.associateWith { mutableSetOf<String>() }.toMutableMap()
        val weighted = ids.associateWith { mutableMapOf<String, Double>() }.toMutableMap()
        graph.adjacency.forEach { (docId, neighbors) ->
            neighbors.forEach { neighbor ->
                undirected.getOrPut(docId) { mutableSetOf() }.add(neighbor.documentId)
                undirected.getOrPut(neighbor.documentId) { mutableSetOf() }.add(docId)
                val similarity = neighbor.similarity.coerceIn(0.0, 1.0)
                weighted.getOrPut(docId) { mutableMapOf() }[neighbor.documentId] = maxOf(
                    weighted[docId]?.get(neighbor.documentId) ?: 0.0,
                    similarity
                )
                weighted.getOrPut(neighbor.documentId) { mutableMapOf() }[docId] = maxOf(
                    weighted[neighbor.documentId]?.get(docId) ?: 0.0,
                    similarity
                )
            }
        }

        val baseCommunities = if (featureFlags.communityClustering) {
            detectCommunities(ids, weighted, treeProperties.communityResolution)
        } else {
            connectedComponents(ids, undirected)
        }
        val consensusResult = if (treeProperties.consensusEnabled) {
            applyConsensusClustering(
                ids = ids,
                weighted = weighted,
                undirected = undirected,
                baseCommunities = baseCommunities
            )
        } else {
            ConsensusResult(
                communities = baseCommunities,
                consensusStrength = 0.0,
                unstableClusterCount = 0,
                strongEdgeCount = 0
            )
        }
        val merged = mergeSmallClusters(
            clusters = consensusResult.communities,
            weighted = weighted,
            minClusterSize = treeProperties.minClusterSize.coerceAtLeast(1),
            minAffinity = treeProperties.clusterMergeMinAffinity.coerceIn(0.0, 1.0)
        )
        var splitOversizedAttempted = 0
        var splitRetryAttempted = 0
        var splitRetrySucceeded = 0
        var splitFallbackUsed = 0

        val bounded = merged.clusters.flatMap { component ->
            if (component.size > maxClusterSize.coerceAtLeast(2)) {
                splitOversizedAttempted += 1
            }
            val splitResult = splitOversizedComponent(
                component = component,
                adjacency = undirected,
                weighted = weighted,
                maxClusterSize = maxClusterSize.coerceAtLeast(2)
            )
            if (splitResult.retryAttempted) {
                splitRetryAttempted += 1
            }
            if (splitResult.retrySucceeded) {
                splitRetrySucceeded += 1
            }
            if (splitResult.fallbackUsed) {
                splitFallbackUsed += 1
            }
            splitResult.components
        }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.size }

        val clusters = bounded.mapIndexed { index, component ->
            TreeCluster(
                id = "cluster-${index + 1}",
                documentIds = component.sorted(),
                qualityScore = clusterQuality(component, weighted)
            )
        }
        val clusterStats = ClusterBuildStats(
            clusterCount = clusters.size,
            mergeAttempted = merged.mergeAttempted,
            merged = merged.merged,
            keptSingleton = merged.keptSingleton,
            splitOversizedAttempted = splitOversizedAttempted,
            splitRetryAttempted = splitRetryAttempted,
            splitRetrySucceeded = splitRetrySucceeded,
            splitFallbackUsed = splitFallbackUsed
        )

        sample.stop(durationTimer)
        docsSummary.record(documents.size.toDouble())
        clustersSummary.record(clusters.size.toDouble())
        clusterCountSummary.record(clusters.size.toDouble())
        clusterAverageSizeSummary.record(clusters.map { it.documentIds.size }.average().takeIf { !it.isNaN() } ?: 0.0)
        modularityProxySummary.record(modularityProxy(ids, weighted, clusters))
        if (treeProperties.consensusEnabled) {
            consensusStrengthSummary.record(consensusResult.consensusStrength.coerceIn(0.0, 1.0))
            unstableClusterCountSummary.record(consensusResult.unstableClusterCount.toDouble())
            logger.info(
                "consensus_cluster_summary enabled=true threshold={} strong_edge_count={} consensus_strength={} unstable_cluster_count={}",
                treeProperties.consensusThreshold,
                consensusResult.strongEdgeCount,
                String.format("%.3f", consensusResult.consensusStrength),
                consensusResult.unstableClusterCount
            )
        }
        logger.info(
            "cluster_guardrail_summary merge_attempted={} merged={} kept_singleton={} split_oversized_attempted={} split_retry_attempted={} split_retry_succeeded={} split_fallback_used={}",
            clusterStats.mergeAttempted,
            clusterStats.merged,
            clusterStats.keptSingleton,
            clusterStats.splitOversizedAttempted,
            clusterStats.splitRetryAttempted,
            clusterStats.splitRetrySucceeded,
            clusterStats.splitFallbackUsed
        )
        return ClusterBuildResult(
            clusters = clusters,
            stats = clusterStats
        )
    }

    private fun applyConsensusClustering(
        ids: List<String>,
        weighted: Map<String, Map<String, Double>>,
        undirected: Map<String, Set<String>>,
        baseCommunities: List<List<String>>
    ): ConsensusResult {
        if (ids.isEmpty()) {
            return ConsensusResult(
                communities = emptyList(),
                consensusStrength = 0.0,
                unstableClusterCount = 0,
                strongEdgeCount = 0
            )
        }

        val ensembles = mutableListOf<List<List<String>>>()
        if (baseCommunities.isNotEmpty()) {
            ensembles += baseCommunities
        }
        val highResolution = detectCommunities(ids, weighted, treeProperties.communityResolution + 0.25)
        if (highResolution.isNotEmpty()) {
            ensembles += highResolution
        }
        val lowResolution = detectCommunities(
            ids,
            weighted,
            (treeProperties.communityResolution - 0.20).coerceAtLeast(0.10)
        )
        if (lowResolution.isNotEmpty()) {
            ensembles += lowResolution
        }
        val denseThreshold = maxOf(0.55, treeProperties.neighborMinSimilarity.coerceIn(0.0, 1.0))
        val denseUndirected = ids.associateWith { mutableSetOf<String>() }.toMutableMap()
        weighted.forEach { (docId, neighbors) ->
            neighbors.forEach { (neighborId, similarity) ->
                if (similarity >= denseThreshold) {
                    denseUndirected.getValue(docId).add(neighborId)
                    denseUndirected.getValue(neighborId).add(docId)
                }
            }
        }
        val denseComponents = connectedComponents(ids, denseUndirected.mapValues { it.value.toSet() })
        if (denseComponents.isNotEmpty()) {
            ensembles += denseComponents
        }
        if (ensembles.isEmpty()) {
            return ConsensusResult(
                communities = connectedComponents(ids, undirected),
                consensusStrength = 0.0,
                unstableClusterCount = 0,
                strongEdgeCount = 0
            )
        }

        val labelMaps = ensembles.map(::clusterLabelMap)
        val threshold = treeProperties.consensusThreshold.coerceIn(0.5, 1.0)
        val strongAdjacency = ids.associateWith { mutableSetOf<String>() }.toMutableMap()
        val pairScoreByKey = mutableMapOf<String, Double>()
        val incidentPairByDoc = ids.associateWith { mutableSetOf<String>() }.toMutableMap()
        var strongEdgeCount = 0
        var totalStrength = 0.0

        weighted.forEach { (docId, neighbors) ->
            neighbors.forEach { (neighborId, _) ->
                if (docId >= neighborId) {
                    return@forEach
                }
                val key = pairKey(docId, neighborId)
                val votes = labelMaps.count { labels -> labels[docId] != null && labels[docId] == labels[neighborId] }
                val probability = votes.toDouble() / labelMaps.size.toDouble()
                pairScoreByKey[key] = probability
                incidentPairByDoc.getValue(docId).add(key)
                incidentPairByDoc.getValue(neighborId).add(key)
                if (probability >= threshold) {
                    strongAdjacency.getValue(docId).add(neighborId)
                    strongAdjacency.getValue(neighborId).add(docId)
                    strongEdgeCount += 1
                    totalStrength += probability
                }
            }
        }

        val communities = if (strongEdgeCount == 0) {
            baseCommunities.ifEmpty { connectedComponents(ids, undirected) }
        } else {
            connectedComponents(ids, strongAdjacency.mapValues { it.value.toSet() })
        }
        val unstableClusterCount = ids.count { docId ->
            val pairKeys = incidentPairByDoc[docId].orEmpty()
            if (pairKeys.isEmpty()) {
                return@count false
            }
            val strongest = pairKeys.maxOfOrNull { pairKey -> pairScoreByKey[pairKey] ?: 0.0 } ?: 0.0
            strongest < threshold
        }
        val consensusStrength = if (strongEdgeCount == 0) {
            0.0
        } else {
            totalStrength / strongEdgeCount.toDouble()
        }
        return ConsensusResult(
            communities = communities,
            consensusStrength = consensusStrength.coerceIn(0.0, 1.0),
            unstableClusterCount = unstableClusterCount,
            strongEdgeCount = strongEdgeCount
        )
    }

    private fun clusterLabelMap(communities: List<List<String>>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        communities.forEachIndexed { index, members ->
            members.forEach { docId ->
                result[docId] = index
            }
        }
        return result
    }

    private fun pairKey(left: String, right: String): String {
        return if (left <= right) {
            "$left::$right"
        } else {
            "$right::$left"
        }
    }

    private fun splitOversizedComponent(
        component: List<String>,
        adjacency: Map<String, Set<String>>,
        weighted: Map<String, Map<String, Double>>,
        maxClusterSize: Int
    ): SplitComponentResult {
        if (component.size <= maxClusterSize) {
            return SplitComponentResult(components = listOf(component))
        }

        val subgraph = component.associateWith { docId ->
            weighted[docId].orEmpty().filterKeys { component.contains(it) }
        }
        val splitByCommunity = detectCommunities(component, subgraph, treeProperties.communityResolution)
            .filter { it.isNotEmpty() }
        if (splitByCommunity.size > 1 && splitByCommunity.all { it.size <= maxClusterSize }) {
            splitClusterCounter.increment()
            return SplitComponentResult(components = splitByCommunity)
        }

        var retryAttempted = false
        if (treeProperties.clusterSplitRetryWithHigherResolution) {
            retryAttempted = true
            val retryResolution = (
                treeProperties.communityResolution *
                    treeProperties.clusterSplitRetryResolutionMultiplier.coerceAtLeast(1.0)
                ).coerceAtLeast(treeProperties.communityResolution + 0.05)
            val retrySplit = detectCommunities(component, subgraph, retryResolution)
                .filter { it.isNotEmpty() }
            if (retrySplit.size > 1 && retrySplit.all { it.size <= maxClusterSize }) {
                splitClusterCounter.increment()
                return SplitComponentResult(
                    components = retrySplit,
                    retryAttempted = true,
                    retrySucceeded = true
                )
            }
        }

        val ordered = component.sortedWith(
            compareByDescending<String> { adjacency[it].orEmpty().size }
                .thenBy { it }
        )

        val buckets = mutableListOf<MutableList<String>>()
        ordered.forEach { docId ->
            val bucket = buckets
                .filter { it.size < maxClusterSize }
                .maxByOrNull { overlap(docId, it, adjacency) }

            if (bucket == null || bucket.size >= maxClusterSize) {
                splitClusterCounter.increment()
                buckets += mutableListOf(docId)
            } else {
                bucket += docId
            }
        }

        return SplitComponentResult(
            components = buckets.map { it.toList() },
            retryAttempted = retryAttempted,
            retrySucceeded = false,
            fallbackUsed = true
        )
    }

    private fun overlap(docId: String, bucket: List<String>, adjacency: Map<String, Set<String>>): Int {
        val neighbors = adjacency[docId].orEmpty()
        return bucket.count { neighbors.contains(it) }
    }

    private fun connectedComponents(ids: List<String>, undirected: Map<String, Set<String>>): List<List<String>> {
        val visited = mutableSetOf<String>()
        val components = mutableListOf<List<String>>()
        ids.forEach { docId ->
            if (!visited.add(docId)) {
                return@forEach
            }
            val queue = ArrayDeque<String>()
            queue.add(docId)
            val component = mutableListOf<String>()

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component += current
                undirected[current].orEmpty().forEach { next ->
                    if (visited.add(next)) {
                        queue.add(next)
                    }
                }
            }
            components += component
        }
        return components
    }

    private fun detectCommunities(
        ids: List<String>,
        weighted: Map<String, Map<String, Double>>,
        resolution: Double
    ): List<List<String>> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        val labels = ids.associateWith { it }.toMutableMap()
        repeat(12) {
            var changed = false
            ids.sortedByDescending { weighted[it].orEmpty().size }.forEach { docId ->
                val scores = mutableMapOf<String, Double>()
                weighted[docId].orEmpty().forEach { (neighborId, weight) ->
                    val label = labels[neighborId] ?: return@forEach
                    scores[label] = (scores[label] ?: 0.0) + weight
                }
                val currentLabel = labels[docId] ?: docId
                scores[currentLabel] = (scores[currentLabel] ?: 0.0) + resolution
                val nextLabel = scores.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                    .firstOrNull()
                    ?.key
                    ?: currentLabel
                if (nextLabel != currentLabel) {
                    labels[docId] = nextLabel
                    changed = true
                }
            }
            if (!changed) {
                return@repeat
            }
        }
        return labels.entries
            .groupBy({ it.value }, { it.key })
            .values
            .map { it.sorted() }
            .sortedByDescending { it.size }
    }

    private fun mergeSmallClusters(
        clusters: List<List<String>>,
        weighted: Map<String, Map<String, Double>>,
        minClusterSize: Int,
        minAffinity: Double
    ): MergeSmallResult {
        if (clusters.isEmpty() || minClusterSize <= 1) {
            return MergeSmallResult(
                clusters = clusters,
                mergeAttempted = 0,
                merged = 0,
                keptSingleton = 0
            )
        }
        val large = clusters.filter { it.size >= minClusterSize }.map { it.toMutableList() }.toMutableList()
        val small = clusters.filter { it.size < minClusterSize }
        if (small.isEmpty()) {
            return MergeSmallResult(
                clusters = clusters,
                mergeAttempted = 0,
                merged = 0,
                keptSingleton = 0
            )
        }
        if (large.isEmpty()) {
            return MergeSmallResult(
                clusters = clusters,
                mergeAttempted = small.size,
                merged = 0,
                keptSingleton = small.size
            )
        }
        var mergeAttempted = 0
        var mergedCount = 0
        var keptSingletonCount = 0
        val keptSingletonClusters = mutableListOf<List<String>>()
        small.forEach { cluster ->
            mergeAttempted += 1
            val scoredTarget = large
                .map { candidate -> candidate to averageAffinity(cluster, candidate, weighted) }
                .maxWithOrNull(
                    compareBy<Pair<MutableList<String>, Double>> { it.second }
                        .thenBy { it.first.joinToString("|") }
                )
            val target = scoredTarget?.first
            val affinity = scoredTarget?.second ?: 0.0
            if (target != null && affinity >= minAffinity) {
                target += cluster
                mergedCount += 1
            } else {
                keptSingletonClusters += cluster.distinct().sorted()
                keptSingletonCount += 1
            }
        }
        val mergedClusters = large.map { it.distinct().sorted() } + keptSingletonClusters
        return MergeSmallResult(
            clusters = mergedClusters
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.size },
            mergeAttempted = mergeAttempted,
            merged = mergedCount,
            keptSingleton = keptSingletonCount
        )
    }

    private fun averageAffinity(
        source: List<String>,
        target: List<String>,
        weighted: Map<String, Map<String, Double>>
    ): Double {
        if (source.isEmpty() || target.isEmpty()) {
            return 0.0
        }
        var total = 0.0
        var count = 0
        source.forEach { sourceDoc ->
            target.forEach { targetDoc ->
                if (sourceDoc == targetDoc) {
                    return@forEach
                }
                total += weighted[sourceDoc]?.get(targetDoc) ?: 0.0
                count += 1
            }
        }
        return if (count == 0) 0.0 else total / count.toDouble()
    }

    private fun clusterQuality(component: List<String>, weighted: Map<String, Map<String, Double>>): Double {
        if (component.size <= 1) {
            return 1.0
        }
        var total = 0.0
        var count = 0
        component.forEachIndexed { index, source ->
            for (target in component.drop(index + 1)) {
                total += weighted[source]?.get(target) ?: 0.0
                count += 1
            }
        }
        return if (count == 0) 0.0 else total / count.toDouble()
    }

    private fun modularityProxy(
        ids: List<String>,
        weighted: Map<String, Map<String, Double>>,
        clusters: List<TreeCluster>
    ): Double {
        if (ids.size <= 1 || clusters.isEmpty()) {
            return 0.0
        }
        val clusterByDoc = mutableMapOf<String, String>()
        clusters.forEach { cluster ->
            cluster.documentIds.forEach { docId ->
                clusterByDoc[docId] = cluster.id
            }
        }
        var internal = 0.0
        var external = 0.0
        var edges = 0
        ids.forEach { docId ->
            weighted[docId].orEmpty().forEach { (neighbor, weight) ->
                if (docId >= neighbor) {
                    return@forEach
                }
                edges += 1
                if (clusterByDoc[docId] == clusterByDoc[neighbor]) {
                    internal += weight
                } else {
                    external += weight
                }
            }
        }
        if (edges == 0) {
            return 0.0
        }
        return (internal - external) / edges.toDouble()
    }
}

@Service
class TreeLabeler(
    private val tokenizer: TreeTokenizer,
    private val featureFlags: com.autodoctree.api.config.FeatureFlags,
    meterRegistry: MeterRegistry
) {
    private val meterRegistryRef = meterRegistry
    private val labelLengthSummary = meterRegistry.summary("avg_label_length")
    private val phraseLabelCounter = meterRegistry.counter("phrase_label_used_total")
    private val mergedLabelCounter = meterRegistry.counter("label_merged_total")
    private val otherClusterCounter = meterRegistry.counter("other_cluster_total")
    private val filteredCounters = mutableMapOf<String, io.micrometer.core.instrument.Counter>()
    private val forbiddenTerms = setOf("porn", "sex", "xxx", "야동", "섹스", "성인", "욕설", "비속어")
    private val genericTerms = setOf("misc", "general", "unknown", "null", "none", "기타", "미분류")

    fun labelClusters(workspaceDocuments: List<DocumentRow>, clusters: List<TreeCluster>): Map<String, String> {
        val docCount = workspaceDocuments.size.coerceAtLeast(1)
        val df = mutableMapOf<String, Int>()
        val docsById = workspaceDocuments.associateBy { it.id }

        workspaceDocuments.forEach { document ->
            tokenize(document.title + " " + (document.bodyText ?: "")).distinct().forEach { token ->
                df[token] = (df[token] ?: 0) + 1
            }
        }

        return clusters.associate { cluster ->
            val tf = mutableMapOf<String, Int>()
            val clusterDf = mutableMapOf<String, Int>()
            val titleDf = mutableMapOf<String, Int>()
            val clusterDocs = cluster.documentIds.mapNotNull { docsById[it] }
            val fallbackLabel = fallbackLabel(clusterDocs)
            clusterDocs.forEach { document ->
                val titleTokens = tokenize(document.title).distinct()
                val bodyTokens = tokenize(document.title + " " + (document.bodyText ?: ""))
                val phraseTokens = phraseCandidates(titleTokens + bodyTokens.take(8))

                bodyTokens.forEach { token ->
                    tf[token] = (tf[token] ?: 0) + 1
                }
                phraseTokens.forEach { phrase ->
                    tf[phrase] = (tf[phrase] ?: 0) + 1
                }
                bodyTokens.distinct().forEach { token ->
                    clusterDf[token] = (clusterDf[token] ?: 0) + 1
                }
                phraseTokens.distinct().forEach { token ->
                    clusterDf[token] = (clusterDf[token] ?: 0) + 1
                }
                titleTokens.forEach { token ->
                    titleDf[token] = (titleDf[token] ?: 0) + 1
                }
            }

            val clusterSize = clusterDocs.size.coerceAtLeast(1).toDouble()
            val bestTerms = tf.entries
                .map { (token, freq) ->
                    val idf = ln((1.0 + docCount) / (1.0 + (df[token] ?: 0))) + 1.0
                    val coverage = (clusterDf[token] ?: 0).toDouble() / clusterSize
                    val titleBoost = if ((titleDf[token] ?: 0) > 0) 1.15 else 1.0
                    val phraseBoost = if (token.contains('-')) 1.2 else 1.0
                    ScoredLabelTerm(
                        token = token,
                        score = (freq.toDouble() * idf) * (0.6 + 0.4 * coverage) * titleBoost * phraseBoost,
                        coverage = coverage
                    )
                }
                .sortedByDescending { it.score }

            val selectedTerms = selectLabelTerms(bestTerms, clusterDocs.size)
            val candidate = sanitizeLabel(selectedTerms)
            val label = finalizeLabel(
                candidate = candidate,
                fallbackLabel = fallbackLabel,
                clusterQualityScore = cluster.qualityScore,
                clusterSize = clusterDocs.size,
                usedPhrase = selectedTerms.any { it.contains('-') }
            )
            cluster.id to label
        }
    }

    fun mergeSimilarLabels(labels: Collection<String>): Map<String, String> {
        if (labels.isEmpty()) {
            return emptyMap()
        }
        val canonical = mutableListOf<String>()
        val mapping = mutableMapOf<String, String>()
        labels.sorted().forEach { raw ->
            val normalized = normalizeLabel(raw)
            val existing = canonical.firstOrNull { candidate ->
                val normalizedCandidate = normalizeLabel(candidate)
                areSimilarLabels(normalized, normalizedCandidate)
            }
            if (existing != null) {
                mapping[raw] = existing
                mergedLabelCounter.increment()
            } else {
                canonical += raw
                mapping[raw] = raw
            }
        }
        return mapping
    }

    fun topLevelLabel(leafLabel: String): String {
        if (leafLabel.contains("기타")) {
            val prefix = leafLabel.substringBefore("-").trim()
            if (prefix.isNotBlank() && prefix != "기타") {
                return prefix.take(32)
            }
            return "general"
        }
        val token = leafLabel.split('-').firstOrNull().orEmpty()
        return if (token.isBlank()) "general" else token.take(32)
    }

    fun keywords(text: String, limit: Int): List<String> {
        return tokenize(text)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit.coerceAtLeast(1))
    }

    fun tokenize(text: String): List<String> {
        return tokenizer.tokenize(text)
    }

    fun fallbackLabelFor(clusterDocs: List<DocumentRow>): String {
        return fallbackLabel(clusterDocs)
    }

    fun finalizeLabel(
        candidate: String,
        fallbackLabel: String,
        clusterQualityScore: Double,
        clusterSize: Int,
        usedPhrase: Boolean = false
    ): String {
        var label = sanitizeLabel(listOf(candidate))
        if (label.isBlank()) {
            label = fallbackLabel
        }
        if (featureFlags.labelQualityFilter) {
            label = filterLabelOrFallback(label, fallbackLabel)
        }
        if (usedPhrase || label.contains('-')) {
            phraseLabelCounter.increment()
        }
        val lowQualityByScore = clusterQualityScore < 0.32
        val lowQualityBySize = clusterSize < 2 && isGenericLikeLabel(label)
        val lowQualityCluster = lowQualityByScore || lowQualityBySize
        if (lowQualityCluster) {
            val topLevel = topLevelLabel(if (label == "기타") fallbackLabel else label)
            label = "$topLevel-기타"
            otherClusterCounter.increment()
        }
        labelLengthSummary.record(label.length.toDouble())
        return label
    }

    private fun sanitizeLabel(terms: List<String>): String {
        val cleaned = terms
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .filterNot { genericTerms.contains(it) }
            .flatMap { it.split('-') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)

        if (cleaned.isEmpty()) {
            return ""
        }

        return cleaned.joinToString("-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "" }
            .take(20)
    }

    private fun isGenericLikeLabel(label: String): Boolean {
        val normalized = label.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank() || genericTerms.contains(normalized)) {
            return true
        }
        val tokens = normalized
            .split('-')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return tokens.isEmpty() || tokens.all { genericTerms.contains(it) }
    }

    private fun selectLabelTerms(scoredTerms: List<ScoredLabelTerm>, clusterSize: Int): List<String> {
        val filtered = if (featureFlags.labelQualityFilter) {
            scoredTerms.filter { candidate -> qualityReason(candidate.token) == null }
        } else {
            scoredTerms
        }
        val primary = filtered.firstOrNull() ?: return emptyList()
        if (clusterSize <= 1) {
            return listOf(primary.token)
        }
        if (primary.token.contains('-')) {
            return listOf(primary.token)
        }

        val minCoverage = if (clusterSize >= 3) 0.6 else 0.75
        val secondary = filtered.drop(1).firstOrNull { candidate ->
            candidate.coverage >= minCoverage &&
                candidate.score >= primary.score * 0.72 &&
                candidate.token != primary.token
        }

        return if (secondary != null) {
            listOf(primary.token, secondary.token)
        } else {
            listOf(primary.token)
        }
    }

    private fun fallbackLabel(clusterDocs: List<DocumentRow>): String {
        val representative = clusterDocs.maxByOrNull { (it.title.length * 10) + (it.bodyText?.length ?: 0) } ?: return "general"
        val tokens = tokenize(representative.title).filter { qualityReason(it) == null }.take(2)
        return sanitizeLabel(tokens).ifBlank { "general" }
    }

    private fun filterLabelOrFallback(label: String, fallbackLabel: String): String {
        val reason = qualityReason(label)
        if (reason == null) {
            return label
        }
        filteredCounters.getOrPut(reason) {
            meterRegistryRef.counter("label_filtered_total", "reason", reason)
        }
        filteredCounters[reason]?.increment()
        val fallbackReason = qualityReason(fallbackLabel)
        return if (fallbackReason == null) fallbackLabel else "general"
    }

    private fun qualityReason(label: String): String? {
        val value = label.lowercase(Locale.ROOT).trim()
        if (value.isBlank()) {
            return "blank"
        }
        if (value.length <= 1) {
            return "too_short"
        }
        if (genericTerms.contains(value)) {
            return "generic"
        }
        if (forbiddenTerms.any { forbidden -> value.contains(forbidden) }) {
            return "forbidden"
        }
        if (value.length <= 2 && !value.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL }) {
            return "too_short"
        }
        if (value.endsWith("하다") || value.endsWith("하는") || value.endsWith("되다")) {
            return "verb_like"
        }
        return null
    }

    private fun phraseCandidates(tokens: List<String>): List<String> {
        val phrases = mutableListOf<String>()
        val cleanTokens = tokens.filter { !it.contains('-') }
        for (size in 2..3) {
            if (cleanTokens.size < size) {
                continue
            }
            for (index in 0..(cleanTokens.size - size)) {
                val phrase = cleanTokens.subList(index, index + size).joinToString("-")
                if (qualityReason(phrase) == null) {
                    phrases += phrase
                }
            }
        }
        return phrases.distinct().take(32)
    }

    private fun normalizeLabel(label: String): String {
        return label
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}-]"), "")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private fun areSimilarLabels(left: String, right: String): Boolean {
        if (left == right) {
            return true
        }
        if (left.isBlank() || right.isBlank()) {
            return false
        }
        if (editDistance(left, right) <= 1) {
            return true
        }
        val leftTokens = left.split('-').filter { it.isNotBlank() }.toSet()
        val rightTokens = right.split('-').filter { it.isNotBlank() }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return false
        }
        val jaccard = leftTokens.intersect(rightTokens).size.toDouble() / leftTokens.union(rightTokens).size.toDouble()
        if (jaccard >= 0.8) {
            return true
        }
        val synonyms = setOf(
            setOf("문서", "도큐먼트", "document"),
            setOf("정리", "분류", "폴더링"),
            setOf("연구", "리서치", "research")
        )
        return synonyms.any { group -> leftTokens.any(group::contains) && rightTokens.any(group::contains) }
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }
}

data class ScoredLabelTerm(
    val token: String,
    val score: Double,
    val coverage: Double
)

@Service
class TreePersonalizationEngine(
    private val objectMapper: ObjectMapper,
    private val treeProperties: TreeProperties
) {
    fun buildModel(
        feedbackEvents: List<FeedbackEventRow>,
        activeNodes: List<TreeNodeRow>,
        documents: List<DocumentRow>,
        tokenizer: (String) -> List<String>,
        embeddings: Map<String, EmbeddingRow> = emptyMap(),
        routingV2Enabled: Boolean = false
    ): PersonalizationModel {
        val now = LocalDateTime.now()
        val nodeLabelById = activeNodes.associate { it.id to it.label }
        val docById = documents.associateBy { it.id }
        val docScores = mutableMapOf<String, MutableMap<String, Double>>()
        val keywordScores = mutableMapOf<String, MutableMap<String, Double>>()
        val entityScores = mutableMapOf<String, MutableMap<String, Double>>()
        val vectorByDoc = if (routingV2Enabled) {
            embeddings
                .mapValues { (_, row) -> parseVector(row.vectorJson) }
                .filterValues { it.isNotEmpty() }
        } else {
            emptyMap()
        }

        feedbackEvents
            .filter { it.eventType == "MOVE" || (routingV2Enabled && it.eventType == "RENAME") }
            .forEachIndexed { index, event ->
                val ageHours = ChronoUnit.HOURS.between(event.createdAt, now).coerceAtLeast(0)
                val recencyWeight = treeProperties.personalizationDecay.pow(index.toDouble())
                val timeWeight = exp(-ageHours.toDouble() / 72.0)
                val weight = recencyWeight * timeWeight
                val payload = objectMapper.readValue(event.payloadJson, Map::class.java)

                if (event.eventType == "MOVE") {
                    val docId = payload["document_id"]?.toString() ?: return@forEachIndexed
                    val toNodeId = payload["to_node_id"]?.toString() ?: return@forEachIndexed
                    val label = nodeLabelById[toNodeId] ?: return@forEachIndexed

                    addScore(docScores, docId, label, weight * 1.4)
                    val sourceDoc = docById[docId] ?: return@forEachIndexed
                    val sourceText = sourceDoc.title + " " + (sourceDoc.bodyText ?: "")
                    tokenizer(sourceText)
                        .take(16)
                        .forEach { token ->
                            addScore(keywordScores, token, label, weight)
                        }
                    extractEntityTokens(sourceText)
                        .take(16)
                        .forEach { entity ->
                            addScore(entityScores, entity, label, weight * 0.9)
                        }

                    if (routingV2Enabled && vectorByDoc.isNotEmpty()) {
                        propagateEmbeddingSignal(
                            sourceDocId = docId,
                            label = label,
                            baseWeight = weight,
                            vectorByDoc = vectorByDoc,
                            docScores = docScores
                        )
                    }
                    return@forEachIndexed
                }

                val newLabel = payload["new_label"]?.toString()?.trim().orEmpty()
                if (newLabel.isBlank()) {
                    return@forEachIndexed
                }
                val oldLabel = payload["old_label"]?.toString()?.trim().orEmpty()
                tokenizer(newLabel)
                    .take(8)
                    .forEach { token -> addScore(keywordScores, token, newLabel, weight * 0.8) }
                if (oldLabel.isNotBlank()) {
                    tokenizer(oldLabel)
                        .take(8)
                        .forEach { token -> addScore(keywordScores, token, newLabel, weight * 0.4) }
                }
            }

        val immutableDocScores = docScores.mapValues { (_, scores) -> scores.toMap() }
        val immutableKeywordScores = keywordScores.mapValues { (_, scores) -> scores.toMap() }
        val immutableEntityScores = entityScores.mapValues { (_, scores) -> scores.toMap() }

        return PersonalizationModel(
            docLabelScores = immutableDocScores,
            keywordLabelScores = immutableKeywordScores,
            entityLabelScores = immutableEntityScores,
            minScore = treeProperties.personalizationMinScore
        )
    }

    private fun parseVector(vectorJson: String): List<Double> {
        return runCatching {
            objectMapper.readValue(vectorJson, List::class.java)
                .mapNotNull { number -> (number as? Number)?.toDouble() }
        }.getOrElse { emptyList() }
    }

    private fun addScore(
        target: MutableMap<String, MutableMap<String, Double>>,
        key: String,
        label: String,
        weight: Double
    ) {
        if (key.isBlank() || label.isBlank() || weight <= 0.0) {
            return
        }
        target.getOrPut(key) { mutableMapOf() }[label] =
            (target[key]?.get(label) ?: 0.0) + weight
    }

    private fun extractEntityTokens(text: String): List<String> {
        return text
            .split(Regex("[^\\p{L}\\p{N}_-]+"))
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length >= 3 }
            .distinct()
    }

    private fun propagateEmbeddingSignal(
        sourceDocId: String,
        label: String,
        baseWeight: Double,
        vectorByDoc: Map<String, List<Double>>,
        docScores: MutableMap<String, MutableMap<String, Double>>
    ) {
        val source = vectorByDoc[sourceDocId] ?: return
        vectorByDoc.forEach { (candidateId, candidateVector) ->
            if (candidateId == sourceDocId || candidateVector.isEmpty()) {
                return@forEach
            }
            val similarity = cosine(source, candidateVector)
            if (similarity < 0.72) {
                return@forEach
            }
            val propagatedWeight = baseWeight * similarity.coerceAtMost(1.0) * 0.6
            addScore(docScores, candidateId, label, propagatedWeight)
        }
    }

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        val size = minOf(a.size, b.size)
        if (size == 0) {
            return 0.0
        }
        var dot = 0.0
        var an = 0.0
        var bn = 0.0
        for (index in 0 until size) {
            dot += a[index] * b[index]
            an += a[index] * a[index]
            bn += b[index] * b[index]
        }
        if (an == 0.0 || bn == 0.0) {
            return 0.0
        }
        return dot / (sqrt(an) * sqrt(bn))
    }
}
