package com.autodoctree.api.worker

import com.autodoctree.api.config.EmbeddingProperties
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.SectionRow
import org.springframework.stereotype.Component

data class EmbeddingPayload(
    val targetType: String,
    val targetId: String,
    val text: String
)

@Component
class EmbeddingInputPreprocessor(
    private val embeddingProperties: EmbeddingProperties
) {
    fun buildPayloads(document: DocumentRow, sections: List<SectionRow>): List<EmbeddingPayload> {
        val payloads = mutableListOf<EmbeddingPayload>()
        val canonicalBody = denoiseBody(normalizeText(document.bodyText ?: document.bodyMarkdown ?: ""))
        val normalizedTitle = normalizeText(document.title)
        val headingPart = sections
            .asSequence()
            .mapNotNull { it.heading }
            .map(::normalizeText)
            .filter { it.isNotBlank() }
            .distinct()
            .take(embeddingProperties.input.sectionHeadingLimit.coerceAtLeast(1))
            .joinToString(" ")

        payloads += EmbeddingPayload(
            targetType = "TITLE",
            targetId = document.id,
            text = truncate("title: $normalizedTitle")
        )

        val bodySummaryInput = truncate(
            listOf(
                "title: $normalizedTitle",
                if (headingPart.isBlank()) null else "headings: $headingPart",
                summarizeBody(canonicalBody)
            ).filterNotNull().joinToString("\n")
        )
        payloads += EmbeddingPayload(
            targetType = "BODY_SUMMARY",
            targetId = document.id,
            text = bodySummaryInput
        )

        sections
            .filterNot { isNoisySection(it) }
            .take(embeddingProperties.input.sectionCountLimit.coerceAtLeast(1))
            .forEach { section ->
                val sectionText = truncate(
                    listOf(
                        "title: $normalizedTitle",
                        section.heading?.let { "section: ${normalizeText(it)}" },
                        summarizeBody(normalizeText(section.chunkText))
                    ).filterNotNull().joinToString("\n")
                )
                payloads += EmbeddingPayload(
                    targetType = "SECTION",
                    targetId = section.id,
                    text = sectionText
                )
            }

        return payloads
    }

    private fun summarizeBody(body: String): String {
        if (body.isBlank()) {
            return "content:"
        }
        val headChars = embeddingProperties.input.headChars.coerceAtLeast(0)
        val tailChars = embeddingProperties.input.tailChars.coerceAtLeast(0)
        if (body.length <= headChars + tailChars || tailChars == 0) {
            return "content: ${body.take(embeddingProperties.input.maxChars.coerceAtLeast(1))}"
        }
        val head = body.take(headChars)
        val tail = body.takeLast(tailChars)
        return "content: $head ... $tail"
    }

    private fun truncate(text: String): String {
        val maxChars = embeddingProperties.input.maxChars.coerceAtLeast(100)
        return text.take(maxChars)
    }

    private fun normalizeText(value: String): String {
        return value
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun denoiseBody(body: String): String {
        if (body.isBlank()) {
            return body
        }
        val lines = body.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return ""
        }
        val counts = lines.groupingBy { it }.eachCount()
        val filtered = lines.filter { line ->
            val repeated = (counts[line] ?: 0) >= 4 && line.length <= 80
            !repeated
        }
        return filtered.joinToString(" ")
    }

    private fun isNoisySection(section: SectionRow): Boolean {
        val flags = section.qualityFlags
            ?.split(',')
            ?.map { it.trim().uppercase() }
            ?.toSet()
            ?: emptySet()
        return flags.contains("GIBBERISH") || flags.contains("ZERO_LENGTH")
    }
}
