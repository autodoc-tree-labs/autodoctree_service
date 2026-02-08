package com.autodoctree.api.domain

import com.autodoctree.api.config.TreeProperties
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
        val builder = NeighborBuilder(objectMapper, registry)
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

        val graph = builder.build("ws-a", docs, embeddings, topK = 1)

        assertEquals(3, graph.adjacency.size)
        assertTrue(graph.adjacency.values.all { it.size <= 1 })
    }

    @Test
    fun `clusterer splits oversized component`() {
        val clusterer = TreeClusterer(SimpleMeterRegistry())
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
        val labeler = TreeLabeler()
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
    fun `personalization model prefers moved label for similar text`() {
        val labeler = TreeLabeler()
        val engine = TreePersonalizationEngine(
            objectMapper = objectMapper,
            treeProperties = TreeProperties(
                neighborTopK = 3,
                maxClusterSize = 10,
                personalizationDecay = 0.9,
                personalizationMinScore = 0.2
            )
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

    private fun embedding(documentId: String, vector: List<Double>): EmbeddingRow {
        return EmbeddingRow(
            id = "emb-$documentId",
            workspaceId = "ws-a",
            documentId = documentId,
            targetType = "DOCUMENT",
            targetId = documentId,
            vectorJson = objectMapper.writeValueAsString(vector),
            modelVersion = "local-stub-v1",
            createdAt = LocalDateTime.now()
        )
    }
}
