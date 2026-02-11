package com.autodoctree.api.ollama.llm

import com.autodoctree.api.config.LlmProperties
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
class OllamaLlmClient(
    private val objectMapper: ObjectMapper,
    private val llmProperties: LlmProperties,
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(llmProperties.ollama.timeoutMs.coerceAtLeast(1000)))
        .build()

    private val latencyTimer = meterRegistry.timer("llm_generate_latency_ms", "provider", "ollama")
    private val failureCounter = meterRegistry.counter("llm_generate_fail_total", "provider", "ollama")
    private val circuitStateOpenUntilMs = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)

    fun generate(prompt: String): String {
        if (isCircuitOpen()) {
            throw IllegalStateException("Ollama generate circuit is open")
        }

        return requestWithRetry(prompt)
    }

    private fun requestWithRetry(prompt: String): String {
        val retries = llmProperties.ollama.maxRetries.coerceAtLeast(0)
        var attempt = 0
        var lastError: Exception? = null

        while (attempt <= retries) {
            val timerSample = Timer.start()
            try {
                val requestBody = objectMapper.writeValueAsString(
                    mapOf(
                        "model" to llmProperties.ollama.model,
                        "prompt" to prompt,
                        "stream" to false
                    )
                )
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(llmProperties.ollama.baseUrl.trimEnd('/') + "/api/generate"))
                    .timeout(Duration.ofMillis(llmProperties.ollama.timeoutMs.coerceAtLeast(1000)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in setOf(200, 201)) {
                    throw IllegalStateException("Ollama generate request failed with status=${response.statusCode()}")
                }

                val root = objectMapper.readTree(response.body())
                val output = root.path("response").asText("").trim()
                if (output.isBlank()) {
                    throw IllegalStateException("Ollama generate returned blank response")
                }

                consecutiveFailures.set(0)
                return output
            } catch (ex: Exception) {
                lastError = ex
                failureCounter.increment()
                registerFailure()
                logger.warn(
                    "ollama_generate_attempt_failed attempt={} prompt_hash={} prompt_len={} message={}",
                    attempt + 1,
                    shortHash(prompt),
                    prompt.length,
                    ex.message
                )
                if (attempt >= retries) {
                    break
                }
                val backoff = llmProperties.ollama.retryBackoffMs.coerceAtLeast(0)
                val multiplier = (attempt + 1).toLong()
                Thread.sleep(backoff * multiplier)
                attempt += 1
                continue
            } finally {
                timerSample.stop(latencyTimer)
            }
        }

        throw IllegalStateException("Ollama generate failed after retries", lastError)
    }

    private fun registerFailure() {
        val threshold = llmProperties.ollama.circuitFailureThreshold.coerceAtLeast(1)
        val failures = consecutiveFailures.incrementAndGet()
        if (failures < threshold) {
            return
        }
        val openMs = llmProperties.ollama.circuitOpenMs.coerceAtLeast(1000)
        circuitStateOpenUntilMs.set(System.currentTimeMillis() + openMs)
        consecutiveFailures.set(0)
        logger.warn("ollama_generate_circuit_open duration_ms={}", openMs)
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
