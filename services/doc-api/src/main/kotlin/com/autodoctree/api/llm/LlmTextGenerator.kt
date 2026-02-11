package com.autodoctree.api.llm

import com.autodoctree.api.config.LlmProperties
import com.autodoctree.api.ollama.llm.OllamaLlmClient
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

interface LlmTextGenerator {
    fun providerId(): String
    fun modelVersion(): String
    fun generate(prompt: String): String
}

@Component
class NoopLlmTextGenerator : LlmTextGenerator {
    override fun providerId(): String = "none"

    override fun modelVersion(): String = "none"

    override fun generate(prompt: String): String = ""
}

@Component
class OllamaLlmTextGenerator(
    private val llmProperties: LlmProperties,
    private val ollamaLlmClient: OllamaLlmClient
) : LlmTextGenerator {
    override fun providerId(): String = "ollama-generate-v1"

    override fun modelVersion(): String {
        val configured = llmProperties.ollama.model.trim().ifBlank { "llama3.1:8b-instruct" }
        val name = configured.substringBefore(":")
        val tag = configured.substringAfter(":", "latest")
        return "ollama:$name@$tag"
    }

    override fun generate(prompt: String): String = ollamaLlmClient.generate(prompt)
}

@Component
@Primary
class LlmTextGeneratorSelector(
    private val noopLlmTextGenerator: NoopLlmTextGenerator,
    private val ollamaLlmTextGenerator: OllamaLlmTextGenerator,
    private val llmProperties: LlmProperties
) : LlmTextGenerator {
    private val logger = LoggerFactory.getLogger(javaClass)

    private fun selected(): LlmTextGenerator {
        return when (llmProperties.provider.trim().lowercase()) {
            "ollama" -> ollamaLlmTextGenerator
            else -> {
                logger.info("llm_provider_disabled provider={} fallback=noop", llmProperties.provider)
                noopLlmTextGenerator
            }
        }
    }

    override fun providerId(): String = selected().providerId()

    override fun modelVersion(): String = selected().modelVersion()

    override fun generate(prompt: String): String = selected().generate(prompt)
}
