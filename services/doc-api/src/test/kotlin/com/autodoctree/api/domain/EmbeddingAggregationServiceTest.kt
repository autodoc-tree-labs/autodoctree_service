package com.autodoctree.api.domain

import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.EmbeddingRow
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class EmbeddingAggregationServiceTest {
    private val objectMapper = jacksonObjectMapper()
    private val service = EmbeddingAggregationService(objectMapper)

    @Test
    fun `aggregates title body and section centroid vectors with configured weights`() {
        val properties = treeProperties(
            documentWeight = 0.6,
            summaryWeight = 0.3,
            sectionWeight = 0.1
        )
        val rows = listOf(
            embedding("doc-1", "TITLE", listOf(1.0, 0.0)),
            embedding("doc-1", "BODY_SUMMARY", listOf(0.0, 1.0)),
            embedding("doc-1", "SECTION_CENTROID", listOf(1.0, 1.0))
        )

        val result = service.aggregateForTree(rows, properties)
        val row = result.getValue("doc-1")
        val vector = objectMapper.readValue(row.vectorJson, List::class.java).map { (it as Number).toDouble() }

        assertEquals("DOCUMENT", row.targetType)
        assertTrue(vector[0] > vector[1], "title weight should dominate x axis")
        assertTrue(vector[0] in 0.8..1.0)
        assertTrue(vector[1] in 0.4..0.6)
    }

    @Test
    fun `falls back to legacy targets when v2 targets are missing`() {
        val properties = treeProperties(
            documentWeight = 0.7,
            summaryWeight = 0.2,
            sectionWeight = 0.1
        )
        val rows = listOf(
            embedding("doc-2", "DOCUMENT", listOf(0.0, 2.0)),
            embedding("doc-2", "SUMMARY", listOf(2.0, 0.0))
        )

        val result = service.aggregateForTree(rows, properties)
        val vector = objectMapper.readValue(result.getValue("doc-2").vectorJson, List::class.java)
            .map { (it as Number).toDouble() }

        assertTrue(vector[0] > 0.0)
        assertTrue(vector[1] > 0.0)
    }

    @Test
    fun `quality weights downweight low quality body channel`() {
        val properties = treeProperties(
            documentWeight = 0.4,
            summaryWeight = 0.5,
            sectionWeight = 0.1
        )
        val rows = listOf(
            embedding("doc-3", "TITLE", listOf(1.0, 0.0)),
            embedding("doc-3", "BODY_SUMMARY", listOf(0.0, 1.0))
        )

        val normal = service.aggregateForTree(rows, properties).getValue("doc-3")
        val lowQuality = service.aggregateForTree(
            rows,
            properties,
            qualityByDocument = mapOf("doc-3" to EmbeddingQualityWeights(body = 0.1, section = 1.0))
        ).getValue("doc-3")

        val normalVector = objectMapper.readValue(normal.vectorJson, List::class.java).map { (it as Number).toDouble() }
        val lowQualityVector = objectMapper.readValue(lowQuality.vectorJson, List::class.java).map { (it as Number).toDouble() }
        assertTrue(lowQualityVector[0] > normalVector[0], "low quality body should shift weight to title axis")
    }

    @Test
    fun `computes section centroid from section vectors`() {
        val centroid = service.centroidForSections(
            listOf(
                listOf(1.0, 1.0, 1.0),
                listOf(3.0, 3.0, 3.0)
            )
        )
        assertEquals(listOf(2.0, 2.0, 2.0), centroid)
    }

    private fun embedding(documentId: String, targetType: String, vector: List<Double>): EmbeddingRow {
        return EmbeddingRow(
            id = UUID.randomUUID().toString(),
            workspaceId = "ws-1",
            documentId = documentId,
            targetType = targetType,
            targetId = "$targetType-$documentId",
            inputHash = UUID.randomUUID().toString(),
            vectorJson = objectMapper.writeValueAsString(vector),
            modelVersion = "ollama:bge-m3@latest",
            createdAt = LocalDateTime.now()
        )
    }

    private fun treeProperties(
        documentWeight: Double,
        summaryWeight: Double,
        sectionWeight: Double
    ): TreeProperties {
        return TreeProperties(
            neighborTopK = 5,
            neighborMinSimilarity = 0.25,
            neighborNormalize = true,
            maxClusterSize = 20,
            minClusterSize = 2,
            communityResolution = 1.0,
            personalizationDecay = 0.85,
            personalizationMinScore = 1.2,
            fusionSemanticWeight = 0.8,
            fusionLexicalWeight = 0.2,
            fusionLexicalGate = 0.35,
            embeddingDocumentWeight = documentWeight,
            embeddingSummaryWeight = summaryWeight,
            embeddingSectionWeight = sectionWeight,
            otherClusterScoreThreshold = 0.32
        )
    }
}
