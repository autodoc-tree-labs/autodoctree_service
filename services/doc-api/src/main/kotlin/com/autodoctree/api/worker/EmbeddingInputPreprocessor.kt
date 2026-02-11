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
        val canonicalBody = normalizeText(document.bodyText ?: document.bodyMarkdown ?: "")
        val normalizedTitle = normalizeText(document.title)
        val headingPart = sections
            .asSequence()
            .mapNotNull { it.heading }
            .map(::normalizeText)
            .filter { it.isNotBlank() }
            .distinct()
            .take(embeddingProperties.input.sectionHeadingLimit.coerceAtLeast(1))
            .joinToString(" ")

        val documentInput = truncate(
            listOf(
                "title: $normalizedTitle",
                if (headingPart.isBlank()) null else "headings: $headingPart",
                summarizeBody(canonicalBody)
            ).filterNotNull().joinToString("\n")
        )
        payloads += EmbeddingPayload(
            targetType = "DOCUMENT",
            targetId = document.id,
            text = documentInput
        )

        val summaryInput = truncate(
            listOf(
                "title: $normalizedTitle",
                if (headingPart.isBlank()) null else "headings: $headingPart",
                summarizeBody(canonicalBody.take(1200))
            ).filterNotNull().joinToString("\n")
        )
        payloads += EmbeddingPayload(
            targetType = "SUMMARY",
            targetId = document.id,
            text = summaryInput
        )

        sections
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
}

