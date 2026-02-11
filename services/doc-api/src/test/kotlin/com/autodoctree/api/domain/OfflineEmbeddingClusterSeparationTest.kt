package com.autodoctree.api.domain

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.EmbeddingRow
import com.autodoctree.api.worker.EmbeddingProvider
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class OfflineEmbeddingClusterSeparationTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `fake embedding separates two topics without ollama`() {
        val registry = SimpleMeterRegistry()
        val labeler = TreeLabeler(FallbackTokenizer(), featureFlags(), registry)
        val builder = NeighborBuilder(objectMapper, labeler, registry)
        val clusterer = TreeClusterer(treeProperties(), featureFlags(), registry)
        val provider = FakeTopicEmbeddingProvider()

        val docs = listOf(
            doc("f1", "회계 결산 보고", "재무 정산 데이터"),
            doc("f2", "청구서 비용 정리", "예산 조정 문서"),
            doc("s1", "축구 경기 분석", "득점 전술 리뷰"),
            doc("s2", "야구 리그 결과", "선수 기록 비교")
        )

        val vectors = provider.embed(docs.map { it.title + " " + (it.bodyText ?: "") })
        val embeddings = docs.mapIndexed { index, doc ->
            embedding(doc.id, vectors[index], provider.modelVersion())
        }.associateBy { it.documentId }

        val graph = builder.build(
            workspaceId = "ws-test",
            documents = docs,
            embeddings = embeddings,
            topK = 2,
            minSimilarity = 0.45,
            normalize = true,
            semanticWeight = 1.0,
            lexicalWeight = 0.0,
            lexicalGate = 0.0
        )

        val clusters = clusterer.cluster(
            documents = docs,
            graph = graph,
            maxClusterSize = 4
        )

        val clusterByDoc = mutableMapOf<String, String>()
        clusters.forEach { cluster ->
            cluster.documentIds.forEach { docId ->
                clusterByDoc[docId] = cluster.id
            }
        }

        assertEquals(clusterByDoc["f1"], clusterByDoc["f2"])
        assertEquals(clusterByDoc["s1"], clusterByDoc["s2"])
        assertNotEquals(clusterByDoc["f1"], clusterByDoc["s1"])
    }

    private fun featureFlags(): FeatureFlags {
        return FeatureFlags(
            autoTree = true,
            explain = true,
            hybridSearch = false,
            embeddingOllama = false,
            labelQualityFilter = true,
            communityClustering = true,
            noriTokenizer = false,
            feedbackRoutingV2 = false,
            userRulesV1 = false,
            adminTreeDebug = true,
            llmLabeling = false,
            llmExplain = false,
            tfidfLabelerFallback = false
        )
    }

    private fun treeProperties(): TreeProperties {
        return TreeProperties(
            neighborTopK = 2,
            neighborMinSimilarity = 0.45,
            neighborNormalize = true,
            maxClusterSize = 6,
            minClusterSize = 1,
            communityResolution = 1.0,
            personalizationDecay = 0.85,
            personalizationMinScore = 0.8,
            fusionSemanticWeight = 1.0,
            fusionLexicalWeight = 0.0,
            fusionLexicalGate = 0.0,
            otherClusterScoreThreshold = 0.32
        )
    }

    private fun doc(id: String, title: String, body: String): DocumentRow {
        val now = LocalDateTime.now()
        return DocumentRow(
            id = id,
            workspaceId = "ws-test",
            title = title,
            bodyMarkdown = body,
            bodyText = body,
            sourceType = "EDITOR",
            status = "READY",
            version = 1,
            deleted = false,
            createdBy = "u-test",
            createdAt = now,
            updatedAt = now
        )
    }

    private fun embedding(documentId: String, vector: List<Double>, modelVersion: String): EmbeddingRow {
        return EmbeddingRow(
            id = "emb-$documentId",
            workspaceId = "ws-test",
            documentId = documentId,
            targetType = "DOCUMENT",
            targetId = documentId,
            inputHash = "hash-$documentId",
            vectorJson = objectMapper.writeValueAsString(vector),
            modelVersion = modelVersion,
            createdAt = LocalDateTime.now()
        )
    }
}

private class FakeTopicEmbeddingProvider : EmbeddingProvider {
    override fun providerId(): String = "fake-embed"

    override fun modelVersion(): String = "fake-embed:v1"

    override fun batchSize(): Int = 64

    override fun embed(inputs: List<String>): List<List<Double>> {
        return inputs.map { text ->
            val normalized = text.lowercase()
            when {
                normalized.contains("회계") || normalized.contains("결산") || normalized.contains("청구") || normalized.contains("예산") ->
                    listOf(0.95, 0.05, 0.0)
                normalized.contains("축구") || normalized.contains("야구") || normalized.contains("리그") || normalized.contains("득점") ->
                    listOf(0.05, 0.95, 0.0)
                else -> listOf(0.33, 0.33, 0.34)
            }
        }
    }
}
