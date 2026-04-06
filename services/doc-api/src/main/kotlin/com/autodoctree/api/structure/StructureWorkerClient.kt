package com.autodoctree.api.structure

import com.autodoctree.api.config.StructureWorkerProperties
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.domain.tree.NeighborGraph
import com.autodoctree.api.domain.tree.TreeCluster
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class StructureWorkerClient(
    private val objectMapper: ObjectMapper,
    private val properties: StructureWorkerProperties,
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.timeoutMs.coerceAtLeast(200)))
        .build()

    private val latencyTimer = meterRegistry.timer("structure_worker_latency_ms")
    private val requestCounter = meterRegistry.counter("structure_worker_request_total")
    private val errorCounter = meterRegistry.counter("structure_worker_error_total")

    fun inferClusters(workspaceId: String, documents: List<DocumentRow>, graph: NeighborGraph): List<TreeCluster> {
        if (!properties.enabled || documents.isEmpty()) {
            return emptyList()
        }

        val payload = mapOf(
            "documents" to documents.map { document ->
                mapOf(
                    "id" to document.id,
                    "title" to document.title
                )
            },
            "edges" to uniqueEdges(graph),
            "max_depth" to properties.maxDepth
        )

        val root = requestWithRetry(workspaceId, payload)
        val clustersNode = root.path("clusters")
        if (!clustersNode.isArray) {
            throw IllegalStateException("structure worker response missing clusters")
        }

        return clustersNode.mapNotNull { node ->
            parseClusterNode(node)
        }
    }

    private fun uniqueEdges(graph: NeighborGraph): List<Map<String, Any>> {
        val seen = mutableSetOf<String>()
        val edges = mutableListOf<Map<String, Any>>()
        graph.adjacency.forEach { (left, links) ->
            links.forEach { link ->
                val right = link.documentId
                val key = if (left <= right) "$left::$right" else "$right::$left"
                if (!seen.add(key)) {
                    return@forEach
                }
                edges += mapOf(
                    "left" to left,
                    "right" to right,
                    "weight" to link.similarity.coerceIn(0.0, 1.0)
                )
            }
        }
        return edges
    }

    private fun parseClusterNode(node: JsonNode): TreeCluster? {
        val clusterId = node.path("id").asText("").trim()
        val documentIds = node.path("document_ids")
            .takeIf { it.isArray }
            ?.mapNotNull { item -> item.asText("").trim().takeIf { value -> value.isNotBlank() } }
            .orEmpty()
            .distinct()
        if (clusterId.isBlank() || documentIds.isEmpty()) {
            return null
        }
        val qualityScore = node.path("quality_score").asDouble(1.0).coerceIn(0.0, 1.0)
        return TreeCluster(
            id = clusterId,
            documentIds = documentIds,
            qualityScore = qualityScore
        )
    }

    private fun requestWithRetry(workspaceId: String, payload: Map<String, Any>): JsonNode {
        val retries = properties.maxRetries.coerceAtLeast(0)
        var attempt = 0
        var lastError: Exception? = null

        while (attempt <= retries) {
            val timerSample = Timer.start()
            try {
                requestCounter.increment()
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.baseUrl.trimEnd('/') + "/v1/structure/infer"))
                    .timeout(Duration.ofMillis(properties.timeoutMs.coerceAtLeast(200)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    throw IllegalStateException("structure worker status=${response.statusCode()}")
                }
                return objectMapper.readTree(response.body())
            } catch (ex: Exception) {
                lastError = ex
                errorCounter.increment()
                logger.warn(
                    "structure_worker_request_failed workspace_id={} attempt={} message={}",
                    workspaceId,
                    attempt + 1,
                    ex.message
                )
                if (attempt >= retries) {
                    break
                }
                val backoffMs = properties.retryBackoffMs.coerceAtLeast(0)
                Thread.sleep(backoffMs * (attempt + 1L))
                attempt += 1
                continue
            } finally {
                timerSample.stop(latencyTimer)
            }
        }

        throw IllegalStateException("structure worker request failed after retries", lastError)
    }
}
