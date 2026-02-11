package com.autodoctree.api.config

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class ModelConfigurationValidator(
    private val embeddingProperties: EmbeddingProperties,
    private val llmProperties: LlmProperties
) {
    @PostConstruct
    fun validate() {
        val embeddingProvider = embeddingProperties.provider.trim().lowercase()
        val llmProvider = llmProperties.provider.trim().lowercase()
        val embeddingModel = embeddingProperties.ollama.model.trim().lowercase()
        val llmModel = llmProperties.ollama.model.trim().lowercase()

        if (embeddingProvider == "ollama") {
            require(embeddingModel.startsWith("bge-m3")) {
                "Invalid embedding model '$embeddingModel'. embedding.ollama.model must be bge-m3* (embedding-only)."
            }
        }

        if (llmProvider == "ollama") {
            require(llmModel.startsWith("llama3.1:8b-instruct")) {
                "Invalid llm model '$llmModel'. llm.ollama.model must be llama3.1:8b-instruct* (generate-only)."
            }
        }

        if (embeddingModel.contains("llama") || llmModel.startsWith("bge-m3")) {
            throw IllegalStateException(
                "Model route mismatch detected. embedding.ollama.model must be bge-m3 and llm.ollama.model must be llama3.1:8b-instruct."
            )
        }
    }
}
