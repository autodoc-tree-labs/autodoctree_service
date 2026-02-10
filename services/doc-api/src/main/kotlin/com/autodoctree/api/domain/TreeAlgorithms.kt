package com.autodoctree.api.domain

import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.EmbeddingRow
import com.autodoctree.api.db.FeedbackEventRow
import com.autodoctree.api.db.TreeNodeRow
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
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
    val adjacency: Map<String, List<NeighborLink>>
)

data class TreeCluster(
    val id: String,
    val documentIds: List<String>,
    val qualityScore: Double = 1.0
)

data class PersonalizationModel(
    private val docLabelScores: Map<String, Map<String, Double>>,
    private val keywordLabelScores: Map<String, Map<String, Double>>,
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
}

@Service
class NeighborBuilder(
    private val objectMapper: ObjectMapper,
    private val treeLabeler: TreeLabeler,
    meterRegistry: MeterRegistry
) {
    private val durationTimer = meterRegistry.timer("tree.neighbor_builder.duration")
    private val docsSummary = meterRegistry.summary("tree.neighbor_builder.docs")
    private val edgesSummary = meterRegistry.summary("tree.neighbor_builder.edges")
    private val edgesFilteredSummary = meterRegistry.summary("neighbor_edges_filtered_total")
    private val averageSimilaritySummary = meterRegistry.summary("tree.neighbor_builder.avg_similarity")
    private val averageSemanticSummary = meterRegistry.summary("tree.neighbor_builder.avg_sem_similarity")
    private val averageLexicalSummary = meterRegistry.summary("tree.neighbor_builder.avg_lex_similarity")
    private val edgeCreatedCounterEmbeddingOnly =
        meterRegistry.counter("edge_created_total", "reason", "EMBEDDING_ONLY")
    private val edgeCreatedCounterFusion =
        meterRegistry.counter("edge_created_total", "reason", "EMBEDDING_LEXICAL_GATED")
    private val edgeCreatedCounterLexical = meterRegistry.counter("edge_created_total", "reason", "LEXICAL_ONLY")

    fun build(
        workspaceId: String,
        documents: List<DocumentRow>,
        embeddings: Map<String, EmbeddingRow>,
        topK: Int,
        minSimilarity: Double = 0.0,
        normalize: Boolean = true,
        semanticWeight: Double = 0.8,
        lexicalWeight: Double = 0.2,
        lexicalGate: Double = 0.35
    ): NeighborGraph {
        val sample = Timer.start()
        val similarityThreshold = minSimilarity.coerceIn(-1.0, 1.0)
        val useEmbeddingSimilarity = embeddings.values.any { !it.modelVersion.startsWith("local-stub", ignoreCase = true) }
        val vectorByDoc = documents.associate { document ->
            val vector = embeddings[document.id]?.let { embedding ->
                objectMapper.readValue(embedding.vectorJson, List::class.java)
                    .mapNotNull { number -> (number as? Number)?.toDouble() }
            }
            document.id to vector
        }
        val lexicalVectors = buildLexicalVectors(documents)
        val titleTokenSets = documents.associate { document ->
            document.id to treeLabeler.tokenize(document.title).filterNot { it.contains('-') }.toSet()
        }
        val entityTokenSets = documents.associate { document ->
            document.id to extractEntityTokens(document.title + " " + (document.bodyText ?: "")).toSet()
        }

        val adjacency = mutableMapOf<String, MutableList<NeighborLink>>()
        var filteredOutCount = 0
        var totalSimilarity = 0.0
        var totalSemantic = 0.0
        var semanticCount = 0
        var totalLexical = 0.0
        var lexicalCount = 0

        documents.forEach { document ->
            val source = vectorByDoc[document.id].orEmpty()
            val sourceLexical = lexicalVectors[document.id].orEmpty()
            if (source.isEmpty() && !useEmbeddingSimilarity && sourceLexical.isEmpty()) {
                adjacency[document.id] = mutableListOf()
                return@forEach
            }

            val neighbors = documents
                .asSequence()
                .filter { it.id != document.id }
                .mapNotNull { candidate ->
                    val candidateVector = vectorByDoc[candidate.id].orEmpty()
                    val candidateLexical = lexicalVectors[candidate.id].orEmpty()
                    val lexicalSimilarity = cosineSparse(sourceLexical, candidateLexical).coerceIn(0.0, 1.0)
                    val titleOverlap = titleTokenSets[document.id].orEmpty().intersect(
                        titleTokenSets[candidate.id].orEmpty()
                    ).size
                    val sharedEntities = entityTokenSets[document.id].orEmpty().intersect(
                        entityTokenSets[candidate.id].orEmpty()
                    ).size

                    val embeddingSimilarity = if (useEmbeddingSimilarity && source.isNotEmpty() && candidateVector.isNotEmpty()) {
                        cosine(source, candidateVector).let { raw ->
                            if (normalize) normalizeCosine(raw) else raw
                        }
                    } else {
                        null
                    }

                    val lexicalGatePassed = lexicalSimilarity > lexicalGate && (sharedEntities > 0 || titleOverlap > 0)
                    val similarity = if (embeddingSimilarity != null) {
                        val semW = semanticWeight.coerceAtLeast(0.0)
                        val lexW = lexicalWeight.coerceAtLeast(0.0)
                        val effectiveLexW = if (lexicalGatePassed) lexW else 0.0
                        val denominator = (semW + effectiveLexW).coerceAtLeast(0.000001)
                        ((embeddingSimilarity * semW) + (lexicalSimilarity * effectiveLexW)) / denominator
                    } else {
                        lexicalSimilarity
                    }

                    if (similarity < similarityThreshold) {
                        filteredOutCount += 1
                        return@mapNotNull null
                    }

                    totalSimilarity += similarity
                    totalLexical += lexicalSimilarity
                    lexicalCount += 1
                    if (embeddingSimilarity != null) {
                        totalSemantic += embeddingSimilarity
                        semanticCount += 1
                        if (lexicalGatePassed) {
                            edgeCreatedCounterFusion.increment()
                        } else {
                            edgeCreatedCounterEmbeddingOnly.increment()
                        }
                    } else {
                        edgeCreatedCounterLexical.increment()
                    }

                    NeighborLink(
                        documentId = candidate.id,
                        similarity = similarity,
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
                .sortedByDescending { it.similarity }
                .distinctBy { it.documentId }
                .take(topK.coerceAtLeast(1))
                .toMutableList()

            adjacency[document.id] = neighbors
        }

        sample.stop(durationTimer)
        docsSummary.record(documents.size.toDouble())
        edgesSummary.record(adjacency.values.sumOf { it.size }.toDouble())
        edgesFilteredSummary.record(filteredOutCount.toDouble())
        if (adjacency.values.sumOf { it.size } > 0) {
            averageSimilaritySummary.record(totalSimilarity / adjacency.values.sumOf { it.size }.toDouble())
        }
        if (semanticCount > 0) {
            averageSemanticSummary.record(totalSemantic / semanticCount.toDouble())
        }
        if (lexicalCount > 0) {
            averageLexicalSummary.record(totalLexical / lexicalCount.toDouble())
        }

        return NeighborGraph(adjacency = adjacency)
    }

    private fun buildLexicalVectors(documents: List<DocumentRow>): Map<String, Map<String, Double>> {
        if (documents.isEmpty()) {
            return emptyMap()
        }

        val docTokens = documents.associate { document ->
            document.id to treeLabeler.tokenize(document.title + " " + (document.bodyText ?: ""))
                .filterNot { it.contains('-') }
        }
        val docCount = documents.size.toDouble().coerceAtLeast(1.0)
        val df = mutableMapOf<String, Int>()
        docTokens.values.forEach { tokens ->
            tokens.distinct().forEach { token ->
                df[token] = (df[token] ?: 0) + 1
            }
        }

        return docTokens.mapValues { (_, tokens) ->
            val tf = tokens.groupingBy { it }.eachCount()
            tf.mapValues { (token, freq) ->
                val idf = ln((1.0 + docCount) / (1.0 + (df[token] ?: 0))) + 1.0
                freq.toDouble() * idf
            }
        }
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
    private val durationTimer = meterRegistry.timer("tree.clusterer.duration")
    private val docsSummary = meterRegistry.summary("tree.clusterer.docs")
    private val clustersSummary = meterRegistry.summary("tree.clusterer.clusters")
    private val clusterCountSummary = meterRegistry.summary("cluster_count")
    private val clusterAverageSizeSummary = meterRegistry.summary("avg_cluster_size")
    private val modularityProxySummary = meterRegistry.summary("modularity_proxy")
    private val splitClusterCounter = meterRegistry.counter("split_cluster_total")

    fun cluster(documents: List<DocumentRow>, graph: NeighborGraph, maxClusterSize: Int): List<TreeCluster> {
        val sample = Timer.start()
        if (documents.isEmpty()) {
            return emptyList()
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

        val communities = if (featureFlags.communityClustering) {
            detectCommunities(ids, weighted, treeProperties.communityResolution)
        } else {
            connectedComponents(ids, undirected)
        }
        val merged = mergeSmallClusters(communities, weighted, treeProperties.minClusterSize.coerceAtLeast(1))

        val bounded = merged.flatMap { component ->
            splitOversizedComponent(component, undirected, weighted, maxClusterSize.coerceAtLeast(2))
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

        sample.stop(durationTimer)
        docsSummary.record(documents.size.toDouble())
        clustersSummary.record(clusters.size.toDouble())
        clusterCountSummary.record(clusters.size.toDouble())
        clusterAverageSizeSummary.record(clusters.map { it.documentIds.size }.average().takeIf { !it.isNaN() } ?: 0.0)
        modularityProxySummary.record(modularityProxy(ids, weighted, clusters))
        return clusters
    }

    private fun splitOversizedComponent(
        component: List<String>,
        adjacency: Map<String, Set<String>>,
        weighted: Map<String, Map<String, Double>>,
        maxClusterSize: Int
    ): List<List<String>> {
        if (component.size <= maxClusterSize) {
            return listOf(component)
        }

        val subgraph = component.associateWith { docId ->
            weighted[docId].orEmpty().filterKeys { component.contains(it) }
        }
        val splitByCommunity = detectCommunities(component, subgraph, treeProperties.communityResolution + 0.25)
            .filter { it.isNotEmpty() }
        if (splitByCommunity.size > 1 && splitByCommunity.all { it.size <= maxClusterSize }) {
            splitClusterCounter.increment()
            return splitByCommunity
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

        return buckets.map { it.toList() }
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
        minClusterSize: Int
    ): List<List<String>> {
        if (clusters.isEmpty() || minClusterSize <= 1) {
            return clusters
        }
        val large = clusters.filter { it.size >= minClusterSize }.map { it.toMutableList() }.toMutableList()
        val small = clusters.filter { it.size < minClusterSize }
        if (large.isEmpty()) {
            return clusters
        }
        small.forEach { cluster ->
            val target = large.maxByOrNull { candidate ->
                averageAffinity(cluster, candidate, weighted)
            } ?: large.first()
            target += cluster
        }
        return large.map { it.distinct().sorted() }.sortedByDescending { it.size }
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
            var label = sanitizeLabel(selectedTerms)
            if (label.isBlank()) {
                label = fallbackLabel
            }
            if (featureFlags.labelQualityFilter) {
                label = filterLabelOrFallback(label, fallbackLabel)
            }
            if (selectedTerms.any { it.contains('-') }) {
                phraseLabelCounter.increment()
            }
            val lowQualityCluster = cluster.qualityScore < 0.32 || clusterDocs.size < 2
            if (lowQualityCluster) {
                val topLevel = topLevelLabel(if (label == "기타") fallbackLabel else label)
                label = "$topLevel-기타"
                otherClusterCounter.increment()
            }
            labelLengthSummary.record(label.length.toDouble())
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
        tokenizer: (String) -> List<String>
    ): PersonalizationModel {
        val now = LocalDateTime.now()
        val nodeLabelById = activeNodes.associate { it.id to it.label }
        val docById = documents.associateBy { it.id }
        val docScores = mutableMapOf<String, MutableMap<String, Double>>()
        val keywordScores = mutableMapOf<String, MutableMap<String, Double>>()

        feedbackEvents
            .filter { it.eventType == "MOVE" }
            .forEachIndexed { index, event ->
                val payload = objectMapper.readValue(event.payloadJson, Map::class.java)
                val docId = payload["document_id"]?.toString() ?: return@forEachIndexed
                val toNodeId = payload["to_node_id"]?.toString() ?: return@forEachIndexed
                val label = nodeLabelById[toNodeId] ?: return@forEachIndexed

                val ageHours = ChronoUnit.HOURS.between(event.createdAt, now).coerceAtLeast(0)
                val recencyWeight = treeProperties.personalizationDecay.pow(index.toDouble())
                val timeWeight = exp(-ageHours.toDouble() / 72.0)
                val weight = recencyWeight * timeWeight

                docScores.getOrPut(docId) { mutableMapOf() }[label] =
                    (docScores[docId]?.get(label) ?: 0.0) + weight

                val sourceDoc = docById[docId] ?: return@forEachIndexed
                tokenizer(sourceDoc.title + " " + (sourceDoc.bodyText ?: ""))
                    .take(12)
                    .forEach { token ->
                        keywordScores.getOrPut(token) { mutableMapOf() }[label] =
                            (keywordScores[token]?.get(label) ?: 0.0) + weight
                    }
            }

        val immutableDocScores = docScores.mapValues { (_, scores) -> scores.toMap() }
        val immutableKeywordScores = keywordScores.mapValues { (_, scores) -> scores.toMap() }

        return PersonalizationModel(
            docLabelScores = immutableDocScores,
            keywordLabelScores = immutableKeywordScores,
            minScore = treeProperties.personalizationMinScore
        )
    }
}
