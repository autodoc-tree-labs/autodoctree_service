package com.autodoctree.api.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class ModelConfigurationValidatorTest {

    @Test
    fun `valid fixed model pair passes`() {
        assertDoesNotThrow {
            ModelConfigurationValidator(
                embeddingProperties = embeddingProperties("bge-m3"),
                llmProperties = llmProperties("llama3.1:8b-instruct")
            ).validate()
        }
    }

    @Test
    fun `swapped model pair fails fast`() {
        assertThrows<IllegalArgumentException> {
            ModelConfigurationValidator(
                embeddingProperties = embeddingProperties("llama3.1:8b-instruct"),
                llmProperties = llmProperties("bge-m3")
            ).validate()
        }
    }

    @Test
    fun `invalid embedding model fails fast`() {
        assertThrows<IllegalArgumentException> {
            ModelConfigurationValidator(
                embeddingProperties = embeddingProperties("multilingual-e5-large"),
                llmProperties = llmProperties("llama3.1:8b-instruct")
            ).validate()
        }
    }

    private fun embeddingProperties(model: String): EmbeddingProperties {
        return EmbeddingProperties(
            provider = "ollama",
            input = EmbeddingInputProperties(4000, 2400, 1200, 6, 24),
            ollama = OllamaEmbeddingProperties(
                baseUrl = "http://localhost:11434",
                model = model,
                timeoutMs = 5000,
                batchSize = 32,
                maxRetries = 2,
                retryBackoffMs = 100,
                circuitFailureThreshold = 3,
                circuitOpenMs = 15000
            )
        )
    }

    private fun llmProperties(model: String): LlmProperties {
        return LlmProperties(
            provider = "ollama",
            ollama = OllamaLlmProperties(
                baseUrl = "http://localhost:11434",
                model = model,
                timeoutMs = 10000,
                maxRetries = 2,
                retryBackoffMs = 100,
                circuitFailureThreshold = 3,
                circuitOpenMs = 15000
            )
        )
    }
}
