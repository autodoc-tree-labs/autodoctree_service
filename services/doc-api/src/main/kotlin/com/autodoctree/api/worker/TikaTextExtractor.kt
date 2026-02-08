package com.autodoctree.api.worker

import org.apache.tika.Tika
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

data class ExtractionResult(
    val text: String,
    val qualityFlags: Set<String>,
    val failureReason: String? = null
)

@Component
class TikaTextExtractor {
    private val tika = Tika()

    fun extract(bytes: ByteArray, contentType: String?): ExtractionResult {
        if (bytes.isEmpty()) {
            return ExtractionResult(text = "", qualityFlags = setOf("ZERO_LENGTH"))
        }

        if (isEncryptedPdf(bytes, contentType)) {
            return ExtractionResult(text = "", qualityFlags = emptySet(), failureReason = "ENCRYPTED_PDF")
        }

        val extracted = runCatching {
            tika.parseToString(bytes.inputStream())
        }.getOrElse {
            ""
        }

        val text = extracted.trim()
        val flags = mutableSetOf<String>()
        if (text.isBlank()) {
            flags += "ZERO_LENGTH"
        }
        if (text.isNotBlank() && text.length < 40) {
            flags += "TOO_SHORT"
        }
        if (text.isNotBlank() && isGibberish(text)) {
            flags += "GIBBERISH"
        }

        return ExtractionResult(text = text, qualityFlags = flags)
    }

    private fun isEncryptedPdf(bytes: ByteArray, contentType: String?): Boolean {
        val pdfMime = contentType?.contains("pdf", ignoreCase = true) == true
        val prefix = bytes.copyOfRange(0, minOf(bytes.size, 5)).toString(StandardCharsets.ISO_8859_1)
        val pdfHeader = prefix.startsWith("%PDF")
        if (!pdfMime && !pdfHeader) {
            return false
        }

        val scanWindow = bytes.copyOfRange(0, minOf(bytes.size, 64 * 1024)).toString(StandardCharsets.ISO_8859_1)
        return scanWindow.contains("/Encrypt") || scanWindow.contains("/Filter /Standard")
    }

    private fun isGibberish(text: String): Boolean {
        val letters = text.count { it.isLetterOrDigit() }
        val printable = text.count { !it.isWhitespace() }
        if (printable == 0) return true
        return letters.toDouble() / printable.toDouble() < 0.4
    }
}
