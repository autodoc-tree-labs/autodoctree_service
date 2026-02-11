package com.autodoctree.api.domain

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.db.DocumentRow
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class UserRuleMatcherTest {

    private val matcher = UserRuleMatcher(
        treeLabeler = TreeLabeler(
            tokenizer = FallbackTokenizer(),
            featureFlags = FeatureFlags(
                autoTree = true,
                explain = true,
                hybridSearch = false,
                embeddingOllama = false,
                labelQualityFilter = true,
                communityClustering = true,
                noriTokenizer = false,
                feedbackRoutingV2 = true,
                userRulesV1 = true,
                adminTreeDebug = true
            ),
            meterRegistry = SimpleMeterRegistry()
        )
    )

    @Test
    fun `matches title contains rule`() {
        val doc = document("doc-1", "재무 결산 보고서", "분기 손익 분석")
        val rules = listOf(
            ResolvedUserRule("rule-1", "TITLE_CONTAINS", matcher.normalizeRuleValue("결산"), "finance", "HARD")
        )

        val matched = matcher.match(doc, rules)

        assertNotNull(matched)
        assertEquals("finance", matched?.targetLabel)
    }

    @Test
    fun `matches entity contains rule with normalized korean token`() {
        val doc = document("doc-2", "섹스와 성 연구", "사회학 관점 분석")
        val rules = listOf(
            ResolvedUserRule("rule-2", "ENTITY_CONTAINS", matcher.normalizeRuleValue("섹스"), "sensitive", "HARD")
        )

        val matched = matcher.match(doc, rules)

        assertNotNull(matched)
        assertEquals("sensitive", matched?.targetLabel)
    }

    @Test
    fun `returns null when no rule matches`() {
        val doc = document("doc-3", "여행 일정 계획", "항공권과 숙소 예약")
        val rules = listOf(
            ResolvedUserRule("rule-3", "TITLE_CONTAINS", matcher.normalizeRuleValue("재무"), "finance", "HARD")
        )

        val matched = matcher.match(doc, rules)

        assertNull(matched)
    }

    @Test
    fun `matches source type rule`() {
        val doc = document("doc-4", "업로드 문서", "pdf 내용", sourceType = "UPLOAD")
        val rules = listOf(
            ResolvedUserRule("rule-4", "SOURCE_TYPE", matcher.normalizeRuleValue("upload"), "uploads", "HARD")
        )

        val matched = matcher.match(doc, rules)

        assertNotNull(matched)
        assertEquals("uploads", matched?.targetLabel)
    }

    @Test
    fun `matches author rule`() {
        val doc = document("doc-5", "작성자 기반 문서", "author test", createdBy = "owner-1")
        val rules = listOf(
            ResolvedUserRule("rule-5", "AUTHOR", matcher.normalizeRuleValue("owner"), "owner-docs", "SOFT")
        )

        val matched = matcher.match(doc, rules)

        assertNotNull(matched)
        assertEquals("owner-docs", matched?.targetLabel)
    }

    @Test
    fun `matches filename extension from context`() {
        val doc = document("doc-6", "첨부 문서", "파일명 확장자 규칙")
        val rules = listOf(
            ResolvedUserRule("rule-6", "FILENAME_EXT", matcher.normalizeRuleValue("pdf"), "pdf-folder", "HARD")
        )
        val context = UserRuleMatchContext(filenameExtensions = setOf("pdf"))

        val matched = matcher.match(doc, rules, context)

        assertNotNull(matched)
        assertEquals("pdf-folder", matched?.targetLabel)
    }

    @Test
    fun `matches tag rule from hashtags`() {
        val doc = document("doc-7", "릴리즈 #infra", "운영 #devops 체크")
        val rules = listOf(
            ResolvedUserRule("rule-7", "TAG", matcher.normalizeRuleValue("infra"), "ops", "SOFT")
        )

        val matched = matcher.match(doc, rules)

        assertNotNull(matched)
        assertEquals("ops", matched?.targetLabel)
    }

    private fun document(
        id: String,
        title: String,
        body: String,
        sourceType: String = "EDITOR",
        createdBy: String = "u-1"
    ): DocumentRow {
        val now = LocalDateTime.now()
        return DocumentRow(
            id = id,
            workspaceId = "ws-a",
            title = title,
            bodyMarkdown = body,
            bodyText = body,
            sourceType = sourceType,
            status = "READY",
            version = 1,
            deleted = false,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now
        )
    }
}
