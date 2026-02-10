package com.autodoctree.api.domain

import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.EmbeddingRow
import com.autodoctree.api.db.FeedbackEventRow
import com.autodoctree.api.db.TreeNodeRow
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TreeAlgorithmsTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `neighbor builder returns bounded topK adjacency`() {
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "alpha"),
            doc("doc-b", "beta"),
            doc("doc-c", "gamma")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(1.0, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.9, 0.1)),
            "doc-c" to embedding("doc-c", listOf(0.0, 1.0))
        )

        val graph = builder.build("ws-a", docs, embeddings, topK = 1, minSimilarity = 0.0)

        assertEquals(3, graph.adjacency.size)
        assertTrue(graph.adjacency.values.all { it.size <= 1 })
    }

    @Test
    fun `neighbor builder drops links below min similarity`() {
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "alpha"),
            doc("doc-b", "beta"),
            doc("doc-c", "gamma")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(1.0, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.9, 0.1)),
            "doc-c" to embedding("doc-c", listOf(0.0, 1.0))
        )

        val graph = builder.build("ws-a", docs, embeddings, topK = 3, minSimilarity = 0.9)

        assertEquals(listOf("doc-b"), graph.adjacency["doc-a"]?.map { it.documentId })
        assertEquals(listOf("doc-a"), graph.adjacency["doc-b"]?.map { it.documentId })
        assertTrue(graph.adjacency["doc-c"].isNullOrEmpty())
    }

    @Test
    fun `neighbor builder uses lexical fallback for local stub embeddings`() {
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "사회 연구"),
            doc("doc-b", "과학 연구"),
            doc("doc-c", "축구 경기")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(0.1, 0.9), modelVersion = "local-stub-v1"),
            "doc-b" to embedding("doc-b", listOf(0.9, 0.1), modelVersion = "local-stub-v1"),
            "doc-c" to embedding("doc-c", listOf(0.2, 0.8), modelVersion = "local-stub-v1")
        )

        val graph = builder.build("ws-a", docs, embeddings, topK = 2, minSimilarity = 0.2)

        assertTrue(graph.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-b" })
        assertFalse(graph.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-c" })
    }

    @Test
    fun `neighbor builder unifies korean particle variants`() {
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "섹스"),
            doc("doc-b", "섹스와 성"),
            doc("doc-c", "축구 경기")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(0.1, 0.9), modelVersion = "local-stub-v1"),
            "doc-b" to embedding("doc-b", listOf(0.9, 0.1), modelVersion = "local-stub-v1"),
            "doc-c" to embedding("doc-c", listOf(0.2, 0.8), modelVersion = "local-stub-v1")
        )

        val graph = builder.build("ws-a", docs, embeddings, topK = 2, minSimilarity = 0.25)

        assertTrue(graph.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-b" })
        assertFalse(graph.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-c" })
    }

    @Test
    fun `clusterer splits oversized component`() {
        val clusterer = testClusterer(SimpleMeterRegistry())
        val docs = (1..9).map { index -> doc("doc-$index", "document $index") }
        val adjacency = docs.associate { d ->
            d.id to docs.filter { it.id != d.id }.map { NeighborLink(it.id, 0.7) }
        }

        val clusters = clusterer.cluster(docs, NeighborGraph(adjacency), maxClusterSize = 4)

        assertTrue(clusters.size >= 3)
        assertTrue(clusters.all { it.documentIds.size <= 4 })
    }

    @Test
    fun `labeler returns non empty labels`() {
        val labeler = testLabeler()
        val docs = listOf(
            doc("doc-a", "billing invoice payment"),
            doc("doc-b", "billing settlement payment"),
            doc("doc-c", "engineering architecture service")
        )
        val clusters = listOf(
            TreeCluster("cluster-1", listOf("doc-a", "doc-b")),
            TreeCluster("cluster-2", listOf("doc-c"))
        )

        val labels = labeler.labelClusters(docs, clusters)

        assertEquals(2, labels.size)
        assertTrue(labels.values.all { it.isNotBlank() })
        assertTrue(labels.values.all { it.length <= 48 })
    }

    @Test
    fun `labeler keeps labels conservative for mixed clusters`() {
        val labeler = testLabeler()
        val docs = listOf(
            doc("doc-a", "사회 연구"),
            doc("doc-b", "과학 연구"),
            doc("doc-c", "문학 연구")
        )
        val clusters = listOf(TreeCluster("cluster-1", docs.map { it.id }))

        val labels = labeler.labelClusters(docs, clusters)

        val label = labels["cluster-1"] ?: ""
        assertTrue(label.contains("연구"))
        assertFalse(label.contains("--"))
        assertTrue(label.length <= 20)
    }

    @Test
    fun `labeler removes numeric noise from singleton labels`() {
        val labeler = testLabeler()
        val docs = listOf(doc("doc-a", "runtime 1770532490 open"))
        val clusters = listOf(TreeCluster("cluster-1", listOf("doc-a")))

        val label = labeler.labelClusters(docs, clusters)["cluster-1"] ?: ""

        assertFalse(label.contains("1770532490"))
        assertFalse(label.contains("--"))
    }

    @Test
    fun `labeler tokenizes korean text`() {
        val labeler = testLabeler()

        val tokens = labeler.tokenize("사회 연구 문서 자동 분류 테스트")

        assertTrue(tokens.contains("사회"))
        assertTrue(tokens.contains("연구"))
        assertTrue(tokens.contains("자동"))
        assertFalse(tokens.contains("문서"))
        assertFalse(tokens.contains("테스트"))
    }

    @Test
    fun `labeler normalizes korean particles`() {
        val labeler = testLabeler()

        val tokens = labeler.tokenize("섹스와 과학은 연구를 다룹니다")

        assertTrue(tokens.contains("섹스"))
        assertFalse(tokens.contains("섹스와"))
        assertTrue(tokens.contains("과학"))
        assertFalse(tokens.contains("과학은"))
        assertTrue(tokens.contains("연구"))
        assertFalse(tokens.contains("연구를"))
    }

    @Test
    fun `fallback tokenizer creates ngram candidates`() {
        val tokenizer = FallbackTokenizer()

        val tokens = tokenizer.tokenize("과학 연구 자동 분류")

        assertTrue(tokens.contains("과학-연구"))
        assertTrue(tokens.contains("연구-자동"))
        assertTrue(tokens.contains("과학-연구-자동"))
    }

    @Test
    fun `labeler filters forbidden terms from cluster label`() {
        val labeler = testLabeler()
        val docs = listOf(
            doc("doc-a", "섹스 관련 자료"),
            doc("doc-b", "섹스 연구 노트")
        )
        val clusters = listOf(TreeCluster("cluster-1", docs.map { it.id }))

        val label = labeler.labelClusters(docs, clusters).getValue("cluster-1")

        assertFalse(label.contains("섹스"))
        assertTrue(label.isNotBlank())
    }

    @Test
    fun `labeler merges similar labels`() {
        val labeler = testLabeler()

        val mapping = labeler.mergeSimilarLabels(listOf("연구", "리서치", "연구-문학"))

        assertEquals(mapping["연구"], mapping["리서치"])
        assertNotNull(mapping["연구-문학"])
    }

    @Test
    fun `personalization model prefers moved label for similar text`() {
        val labeler = testLabeler()
        val engine = TreePersonalizationEngine(
            objectMapper = objectMapper,
            treeProperties = testTreeProperties()
        )

        val docs = listOf(
            doc("doc-a", "billing invoice reconciliation"),
            doc("doc-b", "billing invoice for march")
        )
        val node = TreeNodeRow(
            id = "node-1",
            workspaceId = "ws-a",
            snapshotId = "snap-1",
            parentId = "root",
            label = "billing",
            depth = 2,
            locked = false,
            createdAt = LocalDateTime.now()
        )
        val event = FeedbackEventRow(
            id = "evt-1",
            workspaceId = "ws-a",
            userId = "u-1",
            eventType = "MOVE",
            payloadJson = objectMapper.writeValueAsString(
                mapOf(
                    "document_id" to "doc-a",
                    "to_node_id" to "node-1"
                )
            ),
            createdAt = LocalDateTime.now()
        )

        val model = engine.buildModel(
            feedbackEvents = listOf(event),
            activeNodes = listOf(node),
            documents = docs,
            tokenizer = labeler::tokenize
        )

        val preferred = model.preferredLabelFor(docs[1], labeler::tokenize)

        assertNotNull(preferred)
        assertEquals("billing", preferred)
        assertFalse(model.hasSignalFor("missing-doc"))
    }

    private fun testLabeler(registry: SimpleMeterRegistry = SimpleMeterRegistry()): TreeLabeler {
        return TreeLabeler(
            tokenizer = FallbackTokenizer(),
            featureFlags = testFeatureFlags(),
            meterRegistry = registry
        )
    }

    private fun testClusterer(registry: SimpleMeterRegistry = SimpleMeterRegistry()): TreeClusterer {
        return TreeClusterer(
            treeProperties = testTreeProperties(),
            featureFlags = testFeatureFlags(),
            meterRegistry = registry
        )
    }

    private fun testTreeProperties(): TreeProperties {
        return TreeProperties(
            neighborTopK = 3,
            neighborMinSimilarity = 0.0,
            neighborNormalize = true,
            maxClusterSize = 10,
            minClusterSize = 2,
            communityResolution = 1.0,
            personalizationDecay = 0.9,
            personalizationMinScore = 0.2,
            fusionSemanticWeight = 0.8,
            fusionLexicalWeight = 0.2,
            fusionLexicalGate = 0.35,
            otherClusterScoreThreshold = 0.32
        )
    }

    private fun testFeatureFlags(): FeatureFlags {
        return FeatureFlags(
            autoTree = true,
            explain = true,
            hybridSearch = false,
            embeddingOllama = false,
            labelQualityFilter = true,
            communityClustering = true,
            noriTokenizer = false,
            feedbackRoutingV2 = true,
            userRulesV1 = false,
            adminTreeDebug = true
        )
    }

    private fun doc(id: String, text: String): DocumentRow {
        val now = LocalDateTime.now()
        return DocumentRow(
            id = id,
            workspaceId = "ws-a",
            title = text,
            bodyMarkdown = text,
            bodyText = text,
            sourceType = "EDITOR",
            status = "READY",
            version = 1,
            deleted = false,
            createdBy = "u-1",
            createdAt = now,
            updatedAt = now
        )
    }

    private fun embedding(documentId: String, vector: List<Double>, modelVersion: String = "test-model-v1"): EmbeddingRow {
        return EmbeddingRow(
            id = "emb-$documentId",
            workspaceId = "ws-a",
            documentId = documentId,
            targetType = "DOCUMENT",
            targetId = documentId,
            inputHash = "hash-$documentId-$modelVersion",
            vectorJson = objectMapper.writeValueAsString(vector),
            modelVersion = modelVersion,
            createdAt = LocalDateTime.now()
        )
    }
}
