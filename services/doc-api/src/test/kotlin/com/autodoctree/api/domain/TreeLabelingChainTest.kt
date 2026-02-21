package com.autodoctree.api.domain

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.llm.LlmTextGenerator
import com.autodoctree.api.db.DocumentRow
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TreeLabelingChainTest {

    @Test
    fun `labeler chain uses llm then cache on repeat rebuild`() {
        val registry = SimpleMeterRegistry()
        val featureFlags = featureFlags(llmLabeling = true, tfidfFallback = true)
        val treeLabeler = TreeLabeler(FallbackTokenizer(), featureFlags, registry)
        val fakeLlm = MutableFakeLlmGenerator("과학 연구")
        val llmLabeler = LlmLabeler(featureFlags, fakeLlm, PromptTemplateLoader(), registry)
        val chain = LabelerChain(
            featureFlags = featureFlags,
            llmLabeler = llmLabeler,
            titlePhraseLabeler = TitlePhraseLabeler(),
            tfidfLabeler = TfidfLabeler(treeLabeler),
            treeLabeler = treeLabeler,
            meterRegistry = registry
        )

        val docs = listOf(
            doc("d1", "과학 연구 개요", "실험 계획과 데이터"),
            doc("d2", "과학 연구 방법", "가설 검증 절차")
        )
        val clusters = listOf(TreeCluster("c1", docs.map { it.id }, qualityScore = 0.91))

        val first = chain.labelClusters(docs, clusters)
        val firstLabel = first.labelsByCluster.getValue("c1")
        assertTrue(firstLabel.contains("과학") && firstLabel.contains("연구"))

        fakeLlm.mode = LlmMode.Throw
        val second = chain.labelClusters(docs, clusters, existingCache = first.labelCacheBySignature)
        val secondLabel = second.labelsByCluster.getValue("c1")

        assertEquals(firstLabel, secondLabel)
        assertEquals(1, second.sourceBreakdown["cache"])
    }

    @Test
    fun `labeler chain falls back to deterministic label when llm fails`() {
        val registry = SimpleMeterRegistry()
        val featureFlags = featureFlags(llmLabeling = true, tfidfFallback = false)
        val treeLabeler = TreeLabeler(FallbackTokenizer(), featureFlags, registry)
        val fakeLlm = MutableFakeLlmGenerator(throwAlways = true)
        val llmLabeler = LlmLabeler(featureFlags, fakeLlm, PromptTemplateLoader(), registry)
        val chain = LabelerChain(
            featureFlags = featureFlags,
            llmLabeler = llmLabeler,
            titlePhraseLabeler = TitlePhraseLabeler(),
            tfidfLabeler = TfidfLabeler(treeLabeler),
            treeLabeler = treeLabeler,
            meterRegistry = registry
        )

        val docs = listOf(
            doc("d1", "사회 연구 보고서", "연구 배경"),
            doc("d2", "사회 연구 자료", "연구 결과")
        )
        val clusters = listOf(TreeCluster("c1", docs.map { it.id }, qualityScore = 0.88))

        val result = chain.labelClusters(docs, clusters)
        val label = result.labelsByCluster.getValue("c1")

        assertNotEquals("general", label)
        assertTrue(label.contains("사회") || label.contains("연구"))
    }

    @Test
    fun `singleton cluster keeps specific label without 기타 suffix`() {
        val registry = SimpleMeterRegistry()
        val featureFlags = featureFlags(llmLabeling = true, tfidfFallback = true)
        val treeLabeler = TreeLabeler(FallbackTokenizer(), featureFlags, registry)
        val llmLabeler = LlmLabeler(featureFlags, MutableFakeLlmGenerator("녹차"), PromptTemplateLoader(), registry)
        val chain = LabelerChain(
            featureFlags = featureFlags,
            llmLabeler = llmLabeler,
            titlePhraseLabeler = TitlePhraseLabeler(),
            tfidfLabeler = TfidfLabeler(treeLabeler),
            treeLabeler = treeLabeler,
            meterRegistry = registry
        )

        val docs = listOf(doc("d1", "녹차의 효능", "카테킨과 항산화"))
        val clusters = listOf(TreeCluster("c1", docs.map { it.id }, qualityScore = 0.95))

        val result = chain.labelClusters(docs, clusters)

        assertEquals("녹차", result.labelsByCluster.getValue("c1"))
    }

    @Test
    fun `low quality cluster still falls back to 기타 suffix`() {
        val registry = SimpleMeterRegistry()
        val featureFlags = featureFlags(llmLabeling = true, tfidfFallback = true)
        val treeLabeler = TreeLabeler(FallbackTokenizer(), featureFlags, registry)
        val llmLabeler = LlmLabeler(featureFlags, MutableFakeLlmGenerator("녹차"), PromptTemplateLoader(), registry)
        val chain = LabelerChain(
            featureFlags = featureFlags,
            llmLabeler = llmLabeler,
            titlePhraseLabeler = TitlePhraseLabeler(),
            tfidfLabeler = TfidfLabeler(treeLabeler),
            treeLabeler = treeLabeler,
            meterRegistry = registry
        )

        val docs = listOf(
            doc("d1", "녹차 가이드", "카테킨"),
            doc("d2", "녹차 추출", "폴리페놀")
        )
        val clusters = listOf(TreeCluster("c1", docs.map { it.id }, qualityScore = 0.1))

        val result = chain.labelClusters(docs, clusters)

        assertEquals("녹차-기타", result.labelsByCluster.getValue("c1"))
    }

    private fun featureFlags(llmLabeling: Boolean, tfidfFallback: Boolean): FeatureFlags {
        return FeatureFlags(
            autoTree = true,
            explain = true,
            hybridSearch = false,
            embeddingOllama = false,
            labelQualityFilter = true,
            communityClustering = true,
            noriTokenizer = false,
            feedbackRoutingV2 = true,
            userRulesV1 = false,
            adminTreeDebug = true,
            llmLabeling = llmLabeling,
            llmExplain = true,
            tfidfLabelerFallback = tfidfFallback
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

private enum class LlmMode {
    Success,
    Throw
}

private class MutableFakeLlmGenerator(
    private val fixedResponse: String = "기본 라벨",
    var mode: LlmMode = LlmMode.Success,
    private val throwAlways: Boolean = false
) : LlmTextGenerator {
    override fun providerId(): String = "fake-llm"

    override fun modelVersion(): String = "ollama:llama3.1@8b-instruct"

    override fun generate(prompt: String): String {
        if (throwAlways || mode == LlmMode.Throw) {
            throw IllegalStateException("llm unavailable")
        }
        return fixedResponse
    }
}
