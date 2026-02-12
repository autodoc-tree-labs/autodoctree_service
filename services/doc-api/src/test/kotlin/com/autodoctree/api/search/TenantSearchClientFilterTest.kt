package com.autodoctree.api.search

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TenantSearchClientFilterTest {

    @Test
    fun `workspace filter is detected when present`() {
        val payload = mapOf(
            "query" to mapOf(
                "bool" to mapOf(
                    "filter" to listOf(
                        mapOf("term" to mapOf("workspace_id" to "ws-a"))
                    )
                )
            )
        )

        assertTrue(hasWorkspaceFilter(payload, "ws-a"))
    }

    @Test
    fun `workspace filter missing is detected`() {
        val payload = mapOf(
            "query" to mapOf(
                "bool" to mapOf(
                    "filter" to listOf(
                        mapOf("term" to mapOf("document_id" to "doc-1"))
                    )
                )
            )
        )

        assertFalse(hasWorkspaceFilter(payload, "ws-a"))
    }

    @Test
    fun `nori template payload applies analyzer on title and body fields`() {
        val payload = buildNoriTemplatePayload(
            indexPattern = "docs-v1-*",
            noriUserDictionaryRules = listOf("오토독트리"),
            synonymRules = listOf("문서,도큐먼트")
        )

        val template = payload["template"] as Map<*, *>
        val settings = template["settings"] as Map<*, *>
        val analysis = settings["analysis"] as Map<*, *>
        val analyzers = analysis["analyzer"] as Map<*, *>
        val koNori = analyzers["ko_nori"] as Map<*, *>
        val mappings = template["mappings"] as Map<*, *>
        val properties = mappings["properties"] as Map<*, *>
        val title = properties["title"] as Map<*, *>
        val body = properties["body"] as Map<*, *>

        assertTrue(payload["index_patterns"] == listOf("docs-v1-*"))
        assertTrue(koNori["tokenizer"] == "ko_nori_tokenizer")
        assertTrue(title["analyzer"] == "ko_nori")
        assertTrue(title["search_analyzer"] == "ko_nori")
        assertTrue(body["analyzer"] == "ko_nori")
        assertTrue(body["search_analyzer"] == "ko_nori")
    }
}
