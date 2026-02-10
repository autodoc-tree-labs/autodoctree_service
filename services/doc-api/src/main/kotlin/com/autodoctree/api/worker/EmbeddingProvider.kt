package com.autodoctree.api.worker

import com.autodoctree.api.config.EmbeddingProperties
import com.autodoctree.api.config.FeatureFlags
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

interface EmbeddingProvider {
    fun providerId(): String
    fun modelVersion(): String
    fun batchSize(): Int
    fun embed(inputs: List<String>): List<List<Double>>
}

@Component
class LocalStubEmbeddingProvider(
    meterRegistry: MeterRegistry
) : EmbeddingProvider {
    private val batchSummary = meterRegistry.summary("embedding_batch_size", "provider", "stub")

    override fun providerId(): String = "local-stub-v1"

    override fun modelVersion(): String = "local-stub-v1"

    override fun batchSize(): Int = 64

    override fun embed(inputs: List<String>): List<List<Double>> {
        batchSummary.record(inputs.size.toDouble())
        return inputs.map { text ->
            val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            val vector = MutableList(12) { 0.0 }
            for (i in vector.indices) {
                val b = hash[i].toInt() and 0xFF
                vector[i] = b / 255.0
            }
            vector
        }
    }
}

@Component
class OllamaEmbeddingProvider(
    private val objectMapper: ObjectMapper,
    private val embeddingProperties: EmbeddingProperties,
    meterRegistry: MeterRegistry
) : EmbeddingProvider {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(embeddingProperties.ollama.timeoutMs.coerceAtLeast(1000)))
        .build()
    private val latencyTimer = meterRegistry.timer("embedding_latency_ms", "provider", "ollama")
    private val failureCounter = meterRegistry.counter("embedding_fail_total", "provider", "ollama")
    private val batchSummary = meterRegistry.summary("embedding_batch_size", "provider", "ollama")
    private val circuitStateOpenUntilMs = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)

    override fun providerId(): String = "ollama-embed-v1"

    override fun modelVersion(): String {
        val configured = embeddingProperties.ollama.model.trim().ifBlank { "bge-m3" }
        val name = configured.substringBefore(":")
        val tag = configured.substringAfter(":", "latest")
        return "ollama:$name@$tag"
    }

    override fun batchSize(): Int = embeddingProperties.ollama.batchSize.coerceAtLeast(1)

    override fun embed(inputs: List<String>): List<List<Double>> {
        if (inputs.isEmpty()) {
            return emptyList()
        }
        if (isCircuitOpen()) {
            throw IllegalStateException("Ollama embedding circuit is open")
        }

        val chunks = inputs.chunked(batchSize())
        val vectors = mutableListOf<List<Double>>()
        chunks.forEach { batch ->
            batchSummary.record(batch.size.toDouble())
            vectors += requestWithRetry(batch)
        }
        return vectors
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
}

@Component
@Primary
class EmbeddingProviderSelector(
    private val localStubEmbeddingProvider: LocalStubEmbeddingProvider,
    private val ollamaEmbeddingProvider: OllamaEmbeddingProvider,
    private val embeddingProperties: EmbeddingProperties,
    private val featureFlags: FeatureFlags
) : EmbeddingProvider {
    private val logger = LoggerFactory.getLogger(javaClass)

    private fun selected(): EmbeddingProvider {
        val configured = embeddingProperties.provider.lowercase()
        if (configured == "ollama") {
            if (!featureFlags.embeddingOllama) {
                logger.warn("embedding_provider_ollama_disabled_fallback provider=stub")
                return localStubEmbeddingProvider
            }
            return ollamaEmbeddingProvider
        }
        return localStubEmbeddingProvider
    }

    override fun providerId(): String = selected().providerId()

    override fun modelVersion(): String = selected().modelVersion()

    override fun batchSize(): Int {
        return max(1, min(512, selected().batchSize()))
    }

    override fun embed(inputs: List<String>): List<List<Double>> = selected().embed(inputs)
}

