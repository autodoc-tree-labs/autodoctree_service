package com.autodoctree.api.ollama.embedding

import com.autodoctree.api.config.EmbeddingProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Component
class OllamaEmbeddingClient(
    private val objectMapper: ObjectMapper,
    private val embeddingProperties: EmbeddingProperties,
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(embeddingProperties.ollama.timeoutMs.coerceAtLeast(1000)))
        .build()

    private val latencyTimer = meterRegistry.timer("embedding_latency_ms", "provider", "ollama")
    private val failureCounter = meterRegistry.counter("embedding_fail_total", "provider", "ollama")
    private val batchSummary = meterRegistry.summary("embedding_batch_size", "provider", "ollama")
    private val circuitStateOpenUntilMs = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)

    fun embedBatch(inputs: List<String>): List<List<Double>> {
        if (inputs.isEmpty()) {
            return emptyList()
        }
        if (isCircuitOpen()) {
            throw IllegalStateException("Ollama embedding circuit is open")
        }

        batchSummary.record(inputs.size.toDouble())
        return requestWithRetry(inputs)
    }

    private fun requestWithRetry(batch: List<String>): List<List<Double>> {
        val retries = embeddingProperties.ollama.maxRetries.coerceAtLeast(0)
        var attempt = 0
        var lastError: Exception? = null

        while (attempt <= retries) {
            val timerSample = Timer.start()
            try {
                val requestBody = objectMapper.writeValueAsString(
                    mapOf(
                        "model" to embeddingProperties.ollama.model,
                        "input" to batch
                    )
                )
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingProperties.ollama.baseUrl.trimEnd('/') + "/api/embed"))
                    .timeout(Duration.ofMillis(embeddingProperties.ollama.timeoutMs.coerceAtLeast(1000)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in setOf(200, 201)) {
                    throw IllegalStateException("Ollama embed request failed with status=${response.statusCode()}")
                }
                val root = objectMapper.readTree(response.body())
                val embeddingsNode = root.path("embeddings")
                val parsed = when {
                    embeddingsNode.isArray -> embeddingsNode.map { node ->
                        node.mapNotNull { value -> value.asDouble() }
                    }

                    root.path("embedding").isArray -> listOf(
                        root.path("embedding").mapNotNull { value -> value.asDouble() }
                    )

                    else -> emptyList()
                }
                if (parsed.size != batch.size) {
                    throw IllegalStateException("Ollama embed response size mismatch")
                }
                consecutiveFailures.set(0)
                return parsed
            } catch (ex: Exception) {
                lastError = ex
                failureCounter.increment()
                registerFailure()
                logger.warn(
                    "ollama_embed_attempt_failed attempt={} input_count={} request_hash={} message={}",
                    attempt + 1,
                    batch.size,
                    shortHash(batch.joinToString("|")),
                    ex.message
                )
                if (attempt >= retries) {
                    break
                }
                val backoff = embeddingProperties.ollama.retryBackoffMs.coerceAtLeast(0)
                val multiplier = (attempt + 1).toLong()
                Thread.sleep(backoff * multiplier)
                attempt += 1
                continue
            } finally {
                timerSample.stop(latencyTimer)
            }
        }

        throw IllegalStateException("Ollama embedding failed after retries", lastError)
    }

    private fun registerFailure() {
        val threshold = embeddingProperties.ollama.circuitFailureThreshold.coerceAtLeast(1)
        val failures = consecutiveFailures.incrementAndGet()
        if (failures < threshold) {
            return
        }
        val openMs = embeddingProperties.ollama.circuitOpenMs.coerceAtLeast(1000)
        circuitStateOpenUntilMs.set(System.currentTimeMillis() + openMs)
        consecutiveFailures.set(0)
        logger.warn("ollama_embedding_circuit_open duration_ms={}", openMs)
    }

    private fun isCircuitOpen(): Boolean {
        val now = System.currentTimeMillis()
        val until = circuitStateOpenUntilMs.get()
        return now < until
    }

    private fun shortHash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }
}
