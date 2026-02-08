package com.autodoctree.api.infra

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObservabilityGuardTest {

    @Test
    fun `structured logging contains trace and tenant fields`() {
        val logbackPath = Paths.get("src/main/resources/logback-spring.xml")
        assertTrue(Files.exists(logbackPath), "Missing logback-spring.xml")

        val content = logbackPath.readText()
        assertTrue(content.contains("trace_id"), "trace_id field missing in structured logging")
        assertTrue(content.contains("request_id"), "request_id field missing in structured logging")
        assertTrue(content.contains("workspace_id"), "workspace_id field missing in structured logging")
    }

    @Test
    fun `logger statements do not include sensitive document content keys`() {
        val forbidden = listOf(
            "body_markdown",
            "body_text",
            "chunk_text",
            "upload_url",
            "vector_json",
            "presigned_url",
            "attachment_content",
            "extracted_text"
        )

        val sourceRoot = Paths.get("src/main/kotlin")
        val violations = mutableListOf<String>()

        Files.walk(sourceRoot)
            .filter { path -> path.toString().endsWith(".kt") }
            .forEach { path ->
                scanFile(path, forbidden, violations)
            }

        assertTrue(
            violations.isEmpty(),
            "Sensitive fields found in logger statements: ${violations.joinToString(" | ")}"
        )
    }

    private fun scanFile(path: Path, forbidden: List<String>, violations: MutableList<String>) {
        Files.readAllLines(path).forEachIndexed { index, rawLine ->
            val line = rawLine.lowercase(Locale.US)
            if (!line.contains("logger.")) {
                return@forEachIndexed
            }
            forbidden.forEach { token ->
                if (line.contains(token)) {
                    violations += "${path.toString()}:${index + 1}:$token"
                }
            }
        }
    }
}
