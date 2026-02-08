package com.autodoctree.api.worker

import org.springframework.stereotype.Component
import java.security.MessageDigest

interface EmbeddingProvider {
    fun modelVersion(): String
    fun embed(inputs: List<String>): List<List<Double>>
}

@Component
class LocalStubEmbeddingProvider : EmbeddingProvider {
    override fun modelVersion(): String = "local-stub-v1"

    override fun embed(inputs: List<String>): List<List<Double>> {
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
