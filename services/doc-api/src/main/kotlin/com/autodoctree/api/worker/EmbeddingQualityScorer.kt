package com.autodoctree.api.worker

import com.autodoctree.api.db.SectionRow
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import kotlin.math.ln

data class EmbeddingQualityScore(
    val qBody: Double,
    val qLayout: Double,
    val qOcr: Double
) {
    fun bodyWeight(): Double {
        return ((qBody * 0.60) + (qLayout * 0.15) + (qOcr * 0.25)).coerceIn(0.0, 1.0)
    }

    fun sectionWeight(): Double {
        return ((qBody * 0.30) + (qLayout * 0.45) + (qOcr * 0.25)).coerceIn(0.0, 1.0)
    }
}

@Component
class EmbeddingQualityScorer(
    meterRegistry: MeterRegistry
) {
    private val qBodySummary = meterRegistry.summary("embedding_quality_q_body")
    private val qLayoutSummary = meterRegistry.summary("embedding_quality_q_layout")
    private val qOcrSummary = meterRegistry.summary("embedding_quality_q_ocr")
    private val lowQualityCounter = meterRegistry.counter("embedding_quality_low_total")

    fun score(bodyText: String, sections: List<SectionRow>): EmbeddingQualityScore {
        val qBody = scoreBody(bodyText)
        val qLayout = scoreLayout(sections)
        val qOcr = scoreOcr(bodyText)
        qBodySummary.record(qBody)
        qLayoutSummary.record(qLayout)
        qOcrSummary.record(qOcr)
        if (qBody < 0.35 || qLayout < 0.35 || qOcr < 0.35) {
            lowQualityCounter.increment()
        }
        return EmbeddingQualityScore(
            qBody = qBody,
            qLayout = qLayout,
            qOcr = qOcr
        )
    }

    private fun scoreBody(bodyText: String): Double {
        val normalized = bodyText
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) {
            return 0.0
        }
        val printable = normalized.count { !it.isWhitespace() }.coerceAtLeast(1)
        val alphaNum = normalized.count { it.isLetterOrDigit() }
        val alphaNumRatio = alphaNum.toDouble() / printable.toDouble()

        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        val uniqueTokenRatio = if (tokens.isEmpty()) 0.0 else {
            tokens.distinct().size.toDouble() / tokens.size.toDouble()
        }
        val length = normalized.length
        val lengthScore = when {
            length < 80 -> length.toDouble() / 80.0
            length > 5000 -> 5000.0 / length.toDouble()
            else -> 1.0
        }.coerceIn(0.0, 1.0)
        return ((alphaNumRatio * 0.45) + (uniqueTokenRatio * 0.35) + (lengthScore * 0.20)).coerceIn(0.0, 1.0)
    }

    private fun scoreLayout(sections: List<SectionRow>): Double {
        if (sections.isEmpty()) {
            return 0.5
        }
        val noiseFlags = setOf("GIBBERISH", "TOO_SHORT", "ZERO_LENGTH")
        val noisyRatio = sections.count { row ->
            row.qualityFlags
                ?.split(',')
                ?.map { it.trim().uppercase() }
                ?.any { noiseFlags.contains(it) } == true
        }.toDouble() / sections.size.toDouble()

        val headingCounts = sections
            .mapNotNull { it.heading?.trim()?.lowercase() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        val repeatedHeadingRatio = if (headingCounts.isEmpty()) {
            0.0
        } else {
            headingCounts.values.count { it >= 3 }.toDouble() / headingCounts.size.toDouble()
        }
        return (1.0 - (noisyRatio * 0.7) - (repeatedHeadingRatio * 0.3)).coerceIn(0.0, 1.0)
    }

    private fun scoreOcr(bodyText: String): Double {
        val normalized = bodyText.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) {
            return 0.0
        }
        val printable = normalized.count { !it.isWhitespace() }.coerceAtLeast(1)
        val suspiciousChars = normalized.count { ch ->
            !(ch.isLetterOrDigit() || ch in setOf('.', ',', ';', ':', '-', '_', '(', ')', '[', ']', '/'))
        }
        val suspiciousRatio = suspiciousChars.toDouble() / printable.toDouble()

        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        val avgTokenLength = tokens.map { it.length }.average().takeIf { !it.isNaN() } ?: 0.0
        val tokenEntropyProxy = ln((tokens.distinct().size + 1).toDouble()) / ln((tokens.size + 1).toDouble())
        val tokenLengthScore = when {
            avgTokenLength <= 0.0 -> 0.0
            avgTokenLength < 2.2 -> avgTokenLength / 2.2
            avgTokenLength > 12.0 -> 12.0 / avgTokenLength
            else -> 1.0
        }.coerceIn(0.0, 1.0)

        return ((1.0 - suspiciousRatio).coerceIn(0.0, 1.0) * 0.5 + tokenLengthScore * 0.2 + tokenEntropyProxy * 0.3)
            .coerceIn(0.0, 1.0)
    }
}
