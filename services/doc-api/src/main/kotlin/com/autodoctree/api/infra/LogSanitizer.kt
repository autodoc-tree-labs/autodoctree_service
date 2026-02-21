package com.autodoctree.api.infra

import com.autodoctree.api.config.SecurityFlags
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class LogSanitizer(
    private val securityFlags: SecurityFlags,
    meterRegistry: MeterRegistry
) {
    private val sanitizedLogCounter = meterRegistry.counter("sanitized_log_count")
    private val blockedKeys = setOf(
        "body_markdown",
        "body_text",
        "blocks_json",
        "chunk_text",
        "vector_json",
        "upload_url",
        "presigned_url",
        "attachment_content",
        "extracted_text"
    )
    private val blockedKeysCompacted = blockedKeys.map(::compactKey).toSet()
    private val blockedKeyTokens = listOf("password", "secret", "token", "authorization")

    fun sanitize(payload: Map<String, Any?>): Map<String, Any?> {
        if (!securityFlags.logSanitizerEnabled) {
            return payload
        }
        val (value, changed) = sanitizeValue(payload, null)
        if (changed) {
            sanitizedLogCounter.increment()
        }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun sanitizeValue(value: Any?, key: String?): Pair<Any?, Boolean> {
        val normalizedKey = key?.trim()?.lowercase().orEmpty()
        if (normalizedKey.isNotBlank() && shouldRedact(normalizedKey)) {
            return "[REDACTED]" to true
        }

        return when (value) {
            null -> null to false
            is String -> {
                val maxStringLength = securityFlags.logMaxStringLength.coerceAtLeast(32)
                if (value.length <= maxStringLength) {
                    value to false
                } else {
                    "${value.take(maxStringLength)}...[truncated]" to true
                }
            }
            is Map<*, *> -> {
                val sanitized = linkedMapOf<String, Any?>()
                var changed = false
                value.forEach { (rawKey, rawValue) ->
                    val childKey = rawKey?.toString().orEmpty()
                    val (sanitizedValue, childChanged) = sanitizeValue(rawValue, childKey)
                    sanitized[childKey] = sanitizedValue
                    if (childChanged) {
                        changed = true
                    }
                }
                sanitized to changed
            }
            is List<*> -> {
                val sanitized = mutableListOf<Any?>()
                var changed = false
                value.forEachIndexed { index, item ->
                    val (sanitizedItem, itemChanged) = sanitizeValue(item, "${normalizedKey}_$index")
                    sanitized += sanitizedItem
                    if (itemChanged) {
                        changed = true
                    }
                }
                sanitized to changed
            }
            else -> value to false
        }
    }

    private fun shouldRedact(key: String): Boolean {
        if (key in blockedKeys) {
            return true
        }
        val compacted = compactKey(key)
        if (compacted in blockedKeysCompacted) {
            return true
        }
        return blockedKeyTokens.any { token ->
            key.contains(token) || compacted.contains(token)
        }
    }

    private fun compactKey(value: String): String {
        return value.lowercase().replace(Regex("[^a-z0-9]"), "")
    }
}
