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
}
