package com.autodoctree.api.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
    fun `workspace filter is detected in knn filter`() {
        val payload = buildKnnPayload(
            workspaceId = "ws-a",
            vectorField = "doc_embedding",
            queryVector = listOf(0.1, 0.2, 0.3),
            size = 5
        )

        assertTrue(hasWorkspaceFilter(payload, "ws-a"))
    }

    @Test
    fun `search v2 template payload includes multilingual fields and vector mapping`() {
        val payload = buildSearchV2TemplatePayload(
            indexPattern = "docs-v2-*",
            capabilities = SearchTemplateCapabilities(
                useNori = true,
                useIcu = true,
                useKnn = true,
                vectorDimension = 12,
                vectorField = "doc_embedding"
            ),
            noriUserDictionaryRules = listOf("오토독트리"),
            synonymRules = listOf("문서,도큐먼트")
        )

        val template = payload["template"] as Map<*, *>
        val settings = template["settings"] as Map<*, *>
        val analysis = settings["analysis"] as Map<*, *>
        val analyzer = analysis["analyzer"] as Map<*, *>
        val properties = (template["mappings"] as Map<*, *>)["properties"] as Map<*, *>
        val title = properties["title"] as Map<*, *>
        val titleFields = title["fields"] as Map<*, *>
        val vector = properties["doc_embedding"] as Map<*, *>

        assertTrue(payload["index_patterns"] == listOf("docs-v2-*"))
        assertTrue(analyzer.containsKey("ko_nori"))
        assertTrue(analyzer.containsKey("std_index"))
        assertTrue(titleFields.containsKey("ko"))
        assertTrue(titleFields.containsKey("std"))
        assertTrue(titleFields.containsKey("edge"))
        assertTrue(titleFields.containsKey("keyword"))
        assertEquals("knn_vector", vector["type"])
        assertEquals(12, vector["dimension"])
    }

    @Test
    fun `bm25 payload uses multi match and workspace filter`() {
        val payload = buildBm25Payload(
            workspaceId = "ws-a",
            query = "과학",
            from = 0,
            size = 20,
            operator = "and",
            minimumShouldMatch = null
        )

        val query = payload["query"] as Map<*, *>
        val bool = query["bool"] as Map<*, *>
        val must = bool["must"] as List<*>
        val firstMust = must.first() as Map<*, *>

        assertTrue(hasWorkspaceFilter(payload, "ws-a"))
        assertTrue(firstMust.containsKey("multi_match"))
    }

    @Test
    fun `rrf merge combines bm25 and knn ranks deterministically`() {
        val bm25 = listOf(
            SearchHit(documentId = "a", title = "A", score = 10.0),
            SearchHit(documentId = "b", title = "B", score = 8.0)
        )
        val knn = listOf(
            SearchHit(documentId = "b", title = "B", score = 0.9),
            SearchHit(documentId = "c", title = "C", score = 0.8)
        )

        val merged = mergeWithRrf(
            bm25Hits = bm25,
            knnHits = knn,
            rrfK = 60,
            page = 0,
            size = 10
        )

        assertEquals(3, merged.size)
        assertEquals("b", merged.first().documentId)
        assertNotNull(merged.first().bm25Rank)
        assertNotNull(merged.first().knnRank)
        assertNotNull(merged.first().rrfScore)
    }
}
