package com.autodoctree.api.domain

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.EmbeddingRow
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TreeQualityRegressionTest {

    private val objectMapper = jacksonObjectMapper()
    private val meterRegistry = SimpleMeterRegistry()
    private val labeler = TreeLabeler(
        tokenizer = FallbackTokenizer(),
        featureFlags = featureFlags(),
        meterRegistry = meterRegistry
    )
    private val neighborBuilder = NeighborBuilder(objectMapper, labeler, meterRegistry)
    private val clusterer = TreeClusterer(
        treeProperties = treeProperties(),
        featureFlags = featureFlags(),
        meterRegistry = meterRegistry
    )

    @Test
    fun `golden set detects tree quality regression`() {
        val fixture = loadFixture()
        val documents = fixture.map { item ->
            document(
                id = item.id,
                title = item.title,
                body = item.body
            )
        }
        val topicByDoc = fixture.associate { it.id to it.topic }
        val embeddings = fixture.associate { item ->
            item.id to embedding(item.id, vectorForTopic(item.topic, item.id))
        }

        val first = runPipeline(documents, embeddings)
        val second = runPipeline(documents, embeddings)

        val forbiddenTerms = setOf("porn", "sex", "xxx", "야동", "섹스", "성인", "욕설", "비속어")
        val labels = first.labels.values.toList()
        val forbiddenRatio = if (labels.isEmpty()) 0.0 else {
            labels.count { label -> forbiddenTerms.any { forbidden -> label.contains(forbidden) } }.toDouble() / labels.size
        }
        val avgLabelLength = labels.map { it.length.toDouble() }.average()
        val phraseRatio = if (labels.isEmpty()) 0.0 else labels.count { it.contains('-') }.toDouble() / labels.size.toDouble()
        val purity = weightedPurity(first.assignments, topicByDoc)
        val churnRatio = churnRatio(first.assignments, second.assignments)
        val report = """
            forbidden_ratio=$forbiddenRatio
            avg_label_length=$avgLabelLength
            phrase_ratio=$phraseRatio
            purity=$purity
            churn_ratio=$churnRatio
            labels=${labels.sorted()}
            assignments_sample=${first.assignments.entries.take(12)}
        """.trimIndent()

        assertEquals(0.0, forbiddenRatio, report)
        assertTrue(avgLabelLength in 2.0..20.0, report)
        assertTrue(phraseRatio >= 0.2, report)
        assertTrue(purity >= 0.75, report)
        assertTrue(churnRatio <= 0.05, report)
    }

    private fun runPipeline(
        documents: List<DocumentRow>,
        embeddings: Map<String, EmbeddingRow>
    ): RegressionResult {
        val graph = neighborBuilder.build(
            workspaceId = "ws-golden",
            documents = documents,
            embeddings = embeddings,
            topK = 6,
            minSimilarity = 0.15,
            normalize = true,
            semanticWeight = 0.8,
            lexicalWeight = 0.2,
            lexicalGate = 0.25
        )
        val clusters = clusterer.cluster(
            documents = documents,
            graph = graph,
            maxClusterSize = 12
        )
        val rawLabels = labeler.labelClusters(
            workspaceDocuments = documents,
            clusters = clusters
        )
        val mergedMap = labeler.mergeSimilarLabels(rawLabels.values)
        val labels = rawLabels.mapValues { (_, label) -> mergedMap[label] ?: label }
        val assignments = mutableMapOf<String, String>()
        clusters.forEach { cluster ->
            val label = labels[cluster.id] ?: "general"
            cluster.documentIds.forEach { docId ->
                assignments[docId] = label
            }
        }
        return RegressionResult(labels = labels, assignments = assignments)
    }

    private fun weightedPurity(assignments: Map<String, String>, topicByDoc: Map<String, String>): Double {
        if (assignments.isEmpty()) {
            return 0.0
        }
        val docsByLabel = assignments.entries.groupBy({ it.value }, { it.key })
        val majorityTotal = docsByLabel.values.sumOf { docIds ->
            docIds.groupingBy { topicByDoc[it].orEmpty() }
                .eachCount()
                .maxByOrNull { it.value }
                ?.value
                ?: 0
        }
        return majorityTotal.toDouble() / assignments.size.toDouble()
    }

    private fun churnRatio(previous: Map<String, String>, current: Map<String, String>): Double {
        if (previous.isEmpty()) {
            return 0.0
        }
        val changed = previous.entries.count { (docId, prevLabel) ->
            current[docId] != prevLabel
        }
        return changed.toDouble() / previous.size.toDouble()
    }

    private fun loadFixture(): List<GoldenDoc> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/tree/golden_set_v1.json")) {
            "missing fixture: /tree/golden_set_v1.json"
        }
        return stream.use {
            objectMapper.readValue(it, objectMapper.typeFactory.constructCollectionType(List::class.java, GoldenDoc::class.java))
        }
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
            feedbackRoutingV2 = true,
            userRulesV1 = true,
            adminTreeDebug = true
        )
    }

    private fun treeProperties(): TreeProperties {
        return TreeProperties(
            neighborTopK = 6,
            neighborMinSimilarity = 0.15,
            neighborNormalize = true,
            maxClusterSize = 12,
            minClusterSize = 2,
            communityResolution = 1.0,
            personalizationDecay = 0.85,
            personalizationMinScore = 0.8,
            fusionSemanticWeight = 0.8,
            fusionLexicalWeight = 0.2,
            fusionLexicalGate = 0.25,
            otherClusterScoreThreshold = 0.32
        )
    }

    private fun vectorForTopic(topic: String, id: String): List<Double> {
        val base = when (topic) {
            "finance" -> listOf(1.0, 0.0, 0.0, 0.0, 0.0)
            "sports" -> listOf(0.0, 1.0, 0.0, 0.0, 0.0)
            "science" -> listOf(0.0, 0.0, 1.0, 0.0, 0.0)
            "literature" -> listOf(0.0, 0.0, 0.0, 1.0, 0.0)
            "travel" -> listOf(0.0, 0.0, 0.0, 0.0, 1.0)
            else -> listOf(0.2, 0.2, 0.2, 0.2, 0.2)
        }
        val jitterSeed = id.hashCode().toLong()
        return base.mapIndexed { index, value ->
            val jitter = (((jitterSeed shr (index * 3)) and 0x07) - 3L).toDouble() * 0.01
            (value + jitter).coerceIn(-1.0, 1.0)
        }
    }

    private fun document(id: String, title: String, body: String): DocumentRow {
        val now = LocalDateTime.now()
        return DocumentRow(
            id = id,
            workspaceId = "ws-golden",
            title = title,
            bodyMarkdown = body,
            bodyText = body,
            sourceType = "EDITOR",
            status = "READY",
            version = 1,
            deleted = false,
            createdBy = "u-golden",
            createdAt = now,
            updatedAt = now
        )
    }

    private fun embedding(documentId: String, vector: List<Double>): EmbeddingRow {
        return EmbeddingRow(
            id = "emb-$documentId",
            workspaceId = "ws-golden",
            documentId = documentId,
            targetType = "DOCUMENT",
            targetId = documentId,
            inputHash = "golden-$documentId",
            vectorJson = objectMapper.writeValueAsString(vector),
            modelVersion = "ollama:bge-m3@test",
            createdAt = LocalDateTime.now()
        )
    }
}

private data class GoldenDoc(
    val id: String,
    val topic: String,
    val title: String,
    val body: String
)

private data class RegressionResult(
    val labels: Map<String, String>,
    val assignments: Map<String, String>
)
