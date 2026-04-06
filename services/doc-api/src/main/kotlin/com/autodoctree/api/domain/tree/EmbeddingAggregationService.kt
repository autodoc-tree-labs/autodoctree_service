package com.autodoctree.api.domain.tree

import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.EmbeddingRow
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import kotlin.math.sqrt

data class EmbeddingQualityWeights(
    val body: Double = 1.0,
    val section: Double = 1.0
)

@Service
class EmbeddingAggregationService(
    private val objectMapper: ObjectMapper
) {
    fun aggregateForTree(
        embeddings: List<EmbeddingRow>,
        treeProperties: TreeProperties,
        qualityByDocument: Map<String, EmbeddingQualityWeights> = emptyMap()
    ): Map<String, EmbeddingRow> {
        if (embeddings.isEmpty()) {
            return emptyMap()
        }

        return embeddings
            .groupBy { it.documentId }
            .mapValues { (documentId, rows) ->
                val latestByType = rows
                    .groupBy { it.targetType.uppercase() }
                    .mapValues { (_, byType) -> byType.maxByOrNull { it.createdAt } ?: byType.first() }

                val titleVector = toVector(latestByType["TITLE"] ?: latestByType["DOCUMENT"])
                val bodyVector = toVector(latestByType["BODY_SUMMARY"] ?: latestByType["SUMMARY"])
                val sectionCentroidVector = toVector(latestByType["SECTION_CENTROID"])
                val sectionVectors = rows
                    .asSequence()
                    .filter { it.targetType.equals("SECTION", ignoreCase = true) }
                    .mapNotNull { toVector(it) }
                    .toList()
                val sectionVector = sectionCentroidVector ?: average(sectionVectors)

                val quality = qualityByDocument[documentId] ?: EmbeddingQualityWeights()
                val components = buildList {
                    titleVector?.let {
                        add(it to treeProperties.embeddingDocumentWeight.coerceAtLeast(0.0))
                    }
                    bodyVector?.let {
                        add(it to (treeProperties.embeddingSummaryWeight * quality.body.coerceIn(0.0, 1.0)).coerceAtLeast(0.0))
                    }
                    sectionVector?.let {
                        add(it to (treeProperties.embeddingSectionWeight * quality.section.coerceIn(0.0, 1.0)).coerceAtLeast(0.0))
                    }
                }

                val vector = when {
                    components.isNotEmpty() && components.sumOf { it.second } > 0.0 -> {
                        normalize(weightedAverage(components))
                    }
                    titleVector != null -> titleVector
                    bodyVector != null -> bodyVector
                    else -> sectionVector
                } ?: emptyList()

                val base = latestByType["TITLE"]
                    ?: latestByType["DOCUMENT"]
                    ?: latestByType["BODY_SUMMARY"]
                    ?: latestByType["SUMMARY"]
                    ?: latestByType["SECTION_CENTROID"]
                    ?: rows.first()
                EmbeddingRow(
                    id = base.id,
                    workspaceId = base.workspaceId,
                    documentId = documentId,
                    targetType = "DOCUMENT",
                    targetId = documentId,
                    inputHash = "tree-aggregate:${base.inputHash}",
                    vectorJson = objectMapper.writeValueAsString(vector),
                    modelVersion = base.modelVersion,
                    createdAt = LocalDateTime.now()
                )
            }
    }

    fun centroidForSections(vectors: List<List<Double>>): List<Double> {
        return average(vectors) ?: emptyList()
    }

    private fun toVector(row: EmbeddingRow?): List<Double>? {
        if (row == null) return null
        return runCatching {
            objectMapper.readValue(row.vectorJson, List::class.java)
                .mapNotNull { (it as? Number)?.toDouble() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun average(vectors: List<List<Double>>): List<Double>? {
        if (vectors.isEmpty()) return null
        val size = vectors.minOfOrNull { it.size } ?: return null
        if (size == 0) return null
        val sum = MutableList(size) { 0.0 }
        vectors.forEach { vector ->
            for (i in 0 until size) {
                sum[i] += vector[i]
            }
        }
        return sum.map { it / vectors.size.toDouble() }
    }

    private fun weightedAverage(components: List<Pair<List<Double>, Double>>): List<Double> {
        val size = components.minOfOrNull { it.first.size } ?: return emptyList()
        if (size == 0) return emptyList()
        val totalWeight = components.sumOf { it.second }.coerceAtLeast(0.000001)
        val merged = MutableList(size) { 0.0 }
        components.forEach { (vector, weight) ->
            for (i in 0 until size) {
                merged[i] += vector[i] * weight
            }
        }
        return merged.map { it / totalWeight }
    }

    private fun normalize(vector: List<Double>): List<Double> {
        var norm = 0.0
        vector.forEach { value -> norm += value * value }
        norm = sqrt(norm)
        if (norm == 0.0) return vector
        return vector.map { it / norm }
    }
}
