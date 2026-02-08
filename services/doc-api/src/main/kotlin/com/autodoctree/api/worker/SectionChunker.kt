package com.autodoctree.api.worker

import com.autodoctree.api.db.SectionRow
import java.time.LocalDateTime
import java.util.UUID

class SectionChunker {
    fun split(
        workspaceId: String,
        documentId: String,
        text: String,
        globalQualityFlags: Set<String> = emptySet()
    ): List<SectionRow> {
        val headings = mutableListOf<Pair<String?, String>>()
        val lines = text.lines()
        var currentHeading: String? = null
        val currentBody = mutableListOf<String>()

        fun flush() {
            if (currentBody.isEmpty()) {
                return
            }
            headings.add(currentHeading to currentBody.joinToString("\n"))
            currentBody.clear()
        }

        lines.forEach { line ->
            if (line.trim().startsWith("#")) {
                flush()
                currentHeading = line.trim().trimStart('#').trim().take(120)
            } else {
                currentBody.add(line)
            }
        }
        flush()

        if (headings.isEmpty()) {
            headings.add(null to text)
        }

        val chunks = mutableListOf<SectionRow>()
        var ord = 0
        headings.forEach { (heading, body) ->
            val normalized = body.trim()
            if (normalized.isEmpty()) {
                return@forEach
            }
            if (normalized.length <= 800) {
                chunks.add(newSection(workspaceId, documentId, ord++, heading, normalized, globalQualityFlags))
                return@forEach
            }

            val chunkSize = 700
            val overlap = 80
            var index = 0
            while (index < normalized.length) {
                val end = minOf(index + chunkSize, normalized.length)
                val chunk = normalized.substring(index, end)
                chunks.add(newSection(workspaceId, documentId, ord++, heading, chunk, globalQualityFlags))
                if (end == normalized.length) {
                    break
                }
                index += (chunkSize - overlap)
            }
        }

        if (chunks.isEmpty()) {
            chunks += newSection(
                workspaceId = workspaceId,
                documentId = documentId,
                ord = 0,
                heading = null,
                chunk = "",
                globalQualityFlags = globalQualityFlags + "ZERO_LENGTH"
            )
        }

        return chunks
    }

    private fun newSection(
        workspaceId: String,
        documentId: String,
        ord: Int,
        heading: String?,
        chunk: String,
        globalQualityFlags: Set<String>
    ): SectionRow {
        val localFlags = mutableSetOf<String>()
        when {
            chunk.isBlank() -> localFlags += "ZERO_LENGTH"
            chunk.length < 40 -> localFlags += "TOO_SHORT"
            isGibberish(chunk) -> localFlags += "GIBBERISH"
        }
        globalQualityFlags.forEach { localFlags += it }

        val quality = if (localFlags.isEmpty()) null else localFlags.sorted().joinToString(",")
        return SectionRow(
            id = UUID.randomUUID().toString(),
            workspaceId = workspaceId,
            documentId = documentId,
            ord = ord,
            heading = heading,
            chunkText = chunk,
            qualityFlags = quality,
            createdAt = LocalDateTime.now()
        )
    }

    private fun isGibberish(text: String): Boolean {
        val letters = text.count { it.isLetterOrDigit() }
        val printable = text.count { !it.isWhitespace() }
        if (printable == 0) return true
        return letters.toDouble() / printable.toDouble() < 0.4
    }
}
