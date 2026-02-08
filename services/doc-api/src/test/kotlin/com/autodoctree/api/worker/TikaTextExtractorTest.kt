package com.autodoctree.api.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TikaTextExtractorTest {

    private val extractor = TikaTextExtractor()

    @Test
    fun `encrypted pdf is marked as ingest failure`() {
        val bytes = "%PDF-1.7\n1 0 obj\n<< /Encrypt 2 0 R >>\nendobj\n%%EOF".toByteArray()
        val result = extractor.extract(bytes, "application/pdf")

        assertEquals("ENCRYPTED_PDF", result.failureReason)
    }

    @Test
    fun `empty input is marked as zero length`() {
        val result = extractor.extract(ByteArray(0), "application/pdf")

        assertTrue(result.qualityFlags.contains("ZERO_LENGTH"))
        assertEquals("", result.text)
    }

    @Test
    fun `non encrypted content produces extraction result without hard failure`() {
        val bytes = "simple text body for extraction".toByteArray()
        val result = extractor.extract(bytes, "text/plain")

        assertNotNull(result)
        assertEquals(null, result.failureReason)
    }
}
