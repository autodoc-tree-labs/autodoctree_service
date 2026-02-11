package com.autodoctree.api.worker

import com.autodoctree.api.config.EmbeddingProperties
import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.ollama.embedding.OllamaEmbeddingClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.security.MessageDigest
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
    private val embeddingProperties: EmbeddingProperties,
    private val ollamaEmbeddingClient: OllamaEmbeddingClient
) : EmbeddingProvider {
    override fun providerId(): String = "ollama-embed-v1"

    override fun modelVersion(): String {
        val configured = embeddingProperties.ollama.model.trim().ifBlank { "bge-m3" }
        val name = configured.substringBefore(":")
        val tag = configured.substringAfter(":", "latest")
        return "ollama:$name@$tag"
    }

    override fun batchSize(): Int = embeddingProperties.ollama.batchSize.coerceAtLeast(1)

    override fun embed(inputs: List<String>): List<List<Double>> {
        val chunks = inputs.chunked(batchSize())
        val vectors = mutableListOf<List<Double>>()
        chunks.forEach { batch ->
            vectors += ollamaEmbeddingClient.embedBatch(batch)
        }
        return vectors
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
