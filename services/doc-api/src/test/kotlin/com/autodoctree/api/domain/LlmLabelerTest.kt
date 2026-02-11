package com.autodoctree.api.domain

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.llm.LlmTextGenerator
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class LlmLabelerTest {

    @Test
    fun `llm labeler returns generated label on success`() {
        val labeler = LlmLabeler(
            featureFlags = flags(),
            llmTextGenerator = object : LlmTextGenerator {
                override fun providerId(): String = "fake"
                override fun modelVersion(): String = "fake:v1"
                override fun generate(prompt: String): String = "연구 분류"
            },
            promptTemplateLoader = PromptTemplateLoader(),
            meterRegistry = SimpleMeterRegistry()
        )

        val label = labeler.labelCluster(listOf(doc("d1", "과학 연구", "데이터")), "기본")

        assertEquals("연구 분류", label)
    }

    @Test
    fun `llm labeler returns null when generator fails`() {
        val labeler = LlmLabeler(
            featureFlags = flags(),
            llmTextGenerator = object : LlmTextGenerator {
                override fun providerId(): String = "fake"
                override fun modelVersion(): String = "fake:v1"
                override fun generate(prompt: String): String {
                    throw IllegalStateException("timeout")
                }
            },
            promptTemplateLoader = PromptTemplateLoader(),
            meterRegistry = SimpleMeterRegistry()
        )

        val label = labeler.labelCluster(listOf(doc("d1", "과학 연구", "데이터")), "기본")

        assertNull(label)
    }

    private fun flags(): FeatureFlags {
        return FeatureFlags(
            autoTree = true,
            explain = true,
            hybridSearch = false,
            embeddingOllama = false,
            labelQualityFilter = true,
            communityClustering = true,
            noriTokenizer = false,
            feedbackRoutingV2 = false,
            userRulesV1 = false,
            adminTreeDebug = true,
            llmLabeling = true,
            llmExplain = true,
            tfidfLabelerFallback = false
        )
    }

    private fun doc(id: String, title: String, body: String): DocumentRow {
        val now = LocalDateTime.now()
        return DocumentRow(
            id = id,
            workspaceId = "ws-1",
            title = title,
            bodyMarkdown = body,
            bodyText = body,
            sourceType = "EDITOR",
            status = "READY",
            version = 1,
            deleted = false,
            createdBy = "u-1",
            createdAt = now,
            updatedAt = now
        )
    }
}
