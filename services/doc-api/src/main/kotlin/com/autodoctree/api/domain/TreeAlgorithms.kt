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
    val similarity: Double
)

data class NeighborGraph(
    val adjacency: Map<String, List<NeighborLink>>
)

data class TreeCluster(
    val id: String,
    val documentIds: List<String>
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

    fun build(
        workspaceId: String,
        documents: List<DocumentRow>,
        embeddings: Map<String, EmbeddingRow>,
        topK: Int,
        minSimilarity: Double = 0.0
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

        val adjacency = mutableMapOf<String, MutableList<NeighborLink>>()
        documents.forEach { document ->
            val source = vectorByDoc[document.id].orEmpty()
            val sourceLexical = lexicalVectors[document.id].orEmpty()
            if (source.isEmpty()) {
                if (!useEmbeddingSimilarity && sourceLexical.isEmpty()) {
                    adjacency[document.id] = mutableListOf()
                    return@forEach
                }
            }

            val neighbors = documents
                .asSequence()
                .filter { it.id != document.id }
                .mapNotNull { candidate ->
                    val candidateVector = vectorByDoc[candidate.id].orEmpty()
                    val candidateLexical = lexicalVectors[candidate.id].orEmpty()
                    val lexicalSimilarity = cosineSparse(sourceLexical, candidateLexical)

                    val embeddingSimilarity = if (useEmbeddingSimilarity && source.isNotEmpty() && candidateVector.isNotEmpty()) {
                        cosine(source, candidateVector)
                    } else {
                        null
                    }

                    val similarity = if (embeddingSimilarity != null) {
                        // Keep trusted embedding signal while allowing lexical overlap as fallback enhancer.
                        maxOf(embeddingSimilarity, lexicalSimilarity)
                    } else {
                        lexicalSimilarity
                    }

                    if (similarity < similarityThreshold) {
                        return@mapNotNull null
                    }
                    NeighborLink(candidate.id, similarity)
                }
                .sortedByDescending { it.similarity }
                .take(topK.coerceAtLeast(1))
                .toMutableList()

            adjacency[document.id] = neighbors
        }

        sample.stop(durationTimer)
        docsSummary.record(documents.size.toDouble())
        edgesSummary.record(adjacency.values.sumOf { it.size }.toDouble())

        return NeighborGraph(adjacency = adjacency)
    }

    private fun buildLexicalVectors(documents: List<DocumentRow>): Map<String, Map<String, Double>> {
        if (documents.isEmpty()) {
            return emptyMap()
        }

        val docTokens = documents.associate { document ->
            document.id to treeLabeler.tokenize(document.title + " " + (document.bodyText ?: ""))
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
    meterRegistry: MeterRegistry
) {
    private val durationTimer = meterRegistry.timer("tree.clusterer.duration")
    private val docsSummary = meterRegistry.summary("tree.clusterer.docs")
    private val clustersSummary = meterRegistry.summary("tree.clusterer.clusters")

    fun cluster(documents: List<DocumentRow>, graph: NeighborGraph, maxClusterSize: Int): List<TreeCluster> {
        val sample = Timer.start()
        if (documents.isEmpty()) {
            return emptyList()
        }

        val ids = documents.map { it.id }
        val undirected = ids.associateWith { mutableSetOf<String>() }.toMutableMap()
        graph.adjacency.forEach { (docId, neighbors) ->
            neighbors.forEach { neighbor ->
                undirected.getOrPut(docId) { mutableSetOf() }.add(neighbor.documentId)
                undirected.getOrPut(neighbor.documentId) { mutableSetOf() }.add(docId)
            }
        }

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

        val bounded = components.flatMap { splitOversizedComponent(it, undirected, maxClusterSize.coerceAtLeast(2)) }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.size }

        val clusters = bounded.mapIndexed { index, component ->
            TreeCluster(id = "cluster-${index + 1}", documentIds = component.sorted())
        }

        sample.stop(durationTimer)
        docsSummary.record(documents.size.toDouble())
        clustersSummary.record(clusters.size.toDouble())
        return clusters
    }

    private fun splitOversizedComponent(
        component: List<String>,
        adjacency: Map<String, Set<String>>,
        maxClusterSize: Int
    ): List<List<String>> {
        if (component.size <= maxClusterSize) {
            return listOf(component)
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
}

@Service
class TreeLabeler {
    private val stopwords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "your", "have", "will", "about", "document",
        "draft", "note", "misc", "general", "null", "none",
        "문서", "초안", "메모", "내용", "테스트", "그리고", "하지만", "관련", "대한", "입니다", "있는"
    )
    private val hangulParticleSuffixes = listOf(
        "으로부터", "로부터", "에게서", "한테서", "에서는", "으로는", "로는", "에게는", "한테는",
        "에게", "한테", "에서", "으로", "로", "와의", "과의", "이랑", "랑",
        "와", "과", "은", "는", "이", "가", "을", "를", "의", "도", "만", "까지", "부터"
    ).sortedByDescending { it.length }

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
            clusterDocs.forEach { document ->
                val titleTokens = tokenize(document.title).distinct()
                val bodyTokens = tokenize(document.title + " " + (document.bodyText ?: ""))

                bodyTokens.forEach { token ->
                    tf[token] = (tf[token] ?: 0) + 1
                }
                bodyTokens.distinct().forEach { token ->
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
                    ScoredLabelTerm(
                        token = token,
                        score = (freq.toDouble() * idf) * (0.6 + 0.4 * coverage) * titleBoost,
                        coverage = coverage
                    )
                }
                .sortedByDescending { it.score }

            val label = sanitizeLabel(selectLabelTerms(bestTerms, clusterDocs.size))
            cluster.id to label
        }
    }

    fun topLevelLabel(leafLabel: String): String {
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
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(Regex("\\s+"))
            .map { normalizeToken(it) }
            .filter { it.length >= minimumLength(it) }
            .filterNot { stopwords.contains(it) }
            .filterNot { isNoisyToken(it) }
    }

    private fun sanitizeLabel(terms: List<String>): String {
        val cleaned = terms
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .filter { it != "misc" && it != "unknown" }
            .distinct()
            .take(2)

        if (cleaned.isEmpty()) {
            return "general"
        }

        return cleaned.joinToString("-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "general" }
            .take(48)
    }

    private fun selectLabelTerms(scoredTerms: List<ScoredLabelTerm>, clusterSize: Int): List<String> {
        val primary = scoredTerms.firstOrNull() ?: return emptyList()
        if (clusterSize <= 1) {
            return listOf(primary.token)
        }

        val minCoverage = if (clusterSize >= 3) 0.6 else 0.75
        val secondary = scoredTerms.drop(1).firstOrNull { candidate ->
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

    private fun isNoisyToken(token: String): Boolean {
        if (token.length > 32) {
            return true
        }
        val digits = token.count { it.isDigit() }
        if (digits == token.length) {
            return true
        }
        if (token.length >= 6 && digits >= token.length / 2) {
            return true
        }
        return false
    }

    private fun normalizeToken(raw: String): String {
        var token = raw.trim()
        if (token.isBlank()) {
            return ""
        }
        if (!token.any { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HANGUL }) {
            return token
        }

        // Remove 1-2 postposition suffixes so `섹스` and `섹스와` map to the same core token.
        repeat(2) {
            val suffix = hangulParticleSuffixes.firstOrNull { candidate ->
                token.endsWith(candidate) && token.length - candidate.length >= 2
            } ?: return@repeat
            token = token.removeSuffix(suffix)
        }
        return token
    }

    private fun minimumLength(token: String): Int {
        val hasHangul = token.any { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HANGUL }
        return if (hasHangul) 2 else 3
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
