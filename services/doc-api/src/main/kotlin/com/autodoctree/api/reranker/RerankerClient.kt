package com.autodoctree.api.reranker

import com.autodoctree.api.config.RerankerProperties
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

data class RerankerPairInput(
    val pairKey: String,
    val leftText: String,
    val rightText: String
)

interface PairRerankerClient {
    fun scorePairs(workspaceId: String, pairs: List<RerankerPairInput>): Map<String, Double>
}

@Component
class RerankerHttpClient(
    private val objectMapper: ObjectMapper,
    private val rerankerProperties: RerankerProperties,
    meterRegistry: MeterRegistry
) : PairRerankerClient {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(rerankerProperties.timeoutMs.coerceAtLeast(100)))
        .build()

    private val latencyTimer = meterRegistry.timer("reranker_latency_ms")
    private val requestCounter = meterRegistry.counter("reranker_request_total")
    private val errorCounter = meterRegistry.counter("reranker_error_total")
    private val batchSummary = meterRegistry.summary("reranker_batch_size")

    override fun scorePairs(workspaceId: String, pairs: List<RerankerPairInput>): Map<String, Double> {
        if (pairs.isEmpty()) {
            return emptyMap()
        }
        if (!rerankerProperties.enabled) {
            throw IllegalStateException("reranker is disabled")
        }

        val scores = mutableMapOf<String, Double>()
        val batchSize = rerankerProperties.batchSize.coerceAtLeast(1)
        pairs.chunked(batchSize).forEach { batch ->
            batchSummary.record(batch.size.toDouble())
            val response = requestWithRetry(workspaceId, batch)
            response.forEach { item ->
                val pairKey = item.path("pair_key").asText("").trim()
                if (pairKey.isBlank()) {
                    return@forEach
                }
                val score = item.path("score").asDouble(0.0).coerceIn(0.0, 1.0)
                scores[pairKey] = score
            }
        }
        return scores
    }

    private fun requestWithRetry(workspaceId: String, batch: List<RerankerPairInput>): List<JsonNode> {
        val maxRetries = rerankerProperties.maxRetries.coerceAtLeast(0)
        var attempt = 0
        var lastError: Exception? = null

        while (attempt <= maxRetries) {
            val timerSample = Timer.start()
            try {
                requestCounter.increment()
                val payload = mapOf(
                    "pairs" to batch.map { pair ->
                        mapOf(
                            "pair_key" to pair.pairKey,
                            "left_text" to pair.leftText,
                            "right_text" to pair.rightText
                        )
                    }
                )
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(rerankerProperties.baseUrl.trimEnd('/') + "/v1/rerank/pairs"))
                    .timeout(Duration.ofMillis(rerankerProperties.timeoutMs.coerceAtLeast(100)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    throw IllegalStateException("reranker status=${response.statusCode()}")
                }
                val root = objectMapper.readTree(response.body())
                val items = root.path("items")
                if (!items.isArray) {
                    throw IllegalStateException("reranker response missing items")
                }
                return items.toList()
            } catch (ex: Exception) {
                lastError = ex
                errorCounter.increment()
                logger.warn(
                    "reranker_request_failed workspace_id={} attempt={} pair_count={} message={}",
                    workspaceId,
                    attempt + 1,
                    batch.size,
                    ex.message
                )
                if (attempt >= maxRetries) {
                    break
                }
                val backoffMs = rerankerProperties.retryBackoffMs.coerceAtLeast(0)
                Thread.sleep(backoffMs * (attempt + 1L))
                attempt += 1
                continue
            } finally {
                timerSample.stop(latencyTimer)
            }
        }

        throw IllegalStateException("reranker request failed after retries", lastError)
    }
}
