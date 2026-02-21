package com.autodoctree.api.infra

import com.autodoctree.api.config.SecurityFlags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogSanitizerTest {

    @Test
    fun `redacts blocked keys and secret tokens recursively`() {
        val meterRegistry = SimpleMeterRegistry()
        val sanitizer = LogSanitizer(
            securityFlags = SecurityFlags(
                osTenantAssert = true,
                logSanitizerEnabled = true,
                logMaxStringLength = 240
            ),
            meterRegistry = meterRegistry
        )

        val payload = mapOf(
            "body_markdown" to "secret body",
            "blocks_json" to """{"type":"doc"}""",
            "nested" to mapOf(
                "apiToken" to "abc123",
                "safe_field" to "ok"
            ),
            "list" to listOf(
                mapOf("chunk_text" to "raw chunk")
            )
        )

        val sanitized = sanitizer.sanitize(payload)
        val nested = sanitized["nested"] as Map<*, *>
        val firstListItem = (sanitized["list"] as List<*>).first() as Map<*, *>

        assertEquals("[REDACTED]", sanitized["body_markdown"])
        assertEquals("[REDACTED]", sanitized["blocks_json"])
        assertEquals("[REDACTED]", nested["apiToken"])
        assertEquals("ok", nested["safe_field"])
        assertEquals("[REDACTED]", firstListItem["chunk_text"])
        assertEquals(1.0, meterRegistry.get("sanitized_log_count").counter().count())
    }

    @Test
    fun `truncates long strings by configured max length`() {
        val meterRegistry = SimpleMeterRegistry()
        val sanitizer = LogSanitizer(
            securityFlags = SecurityFlags(
                osTenantAssert = true,
                logSanitizerEnabled = true,
                logMaxStringLength = 32
            ),
            meterRegistry = meterRegistry
        )

        val longText = "a".repeat(50)
        val sanitized = sanitizer.sanitize(mapOf("message" to longText))

        assertEquals("${"a".repeat(32)}...[truncated]", sanitized["message"])
        assertEquals(1.0, meterRegistry.get("sanitized_log_count").counter().count())
    }

    @Test
    fun `returns original payload when sanitizer is disabled`() {
        val meterRegistry = SimpleMeterRegistry()
        val sanitizer = LogSanitizer(
            securityFlags = SecurityFlags(
                osTenantAssert = true,
                logSanitizerEnabled = false,
                logMaxStringLength = 32
            ),
            meterRegistry = meterRegistry
        )

        val payload = mapOf("body_markdown" to "still-visible-for-disabled-mode")
        val sanitized = sanitizer.sanitize(payload)

        assertTrue(sanitized === payload)
        assertEquals(0.0, meterRegistry.get("sanitized_log_count").counter().count())
    }
}
