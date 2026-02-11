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
            ResolvedUserRule("rule-1", "TITLE_CONTAINS", matcher.normalizeRuleValue("결산"), "finance")
        )

        val matched = matcher.match(doc, rules)

        assertNotNull(matched)
        assertEquals("finance", matched?.targetLabel)
    }

    @Test
    fun `matches entity contains rule with normalized korean token`() {
        val doc = document("doc-2", "섹스와 성 연구", "사회학 관점 분석")
        val rules = listOf(
            ResolvedUserRule("rule-2", "ENTITY_CONTAINS", matcher.normalizeRuleValue("섹스"), "sensitive")
        )

        val matched = matcher.match(doc, rules)

        assertNotNull(matched)
        assertEquals("sensitive", matched?.targetLabel)
    }

    @Test
    fun `returns null when no rule matches`() {
        val doc = document("doc-3", "여행 일정 계획", "항공권과 숙소 예약")
        val rules = listOf(
            ResolvedUserRule("rule-3", "TITLE_CONTAINS", matcher.normalizeRuleValue("재무"), "finance")
        )

        val matched = matcher.match(doc, rules)

        assertNull(matched)
    }

    private fun document(id: String, title: String, body: String): DocumentRow {
        val now = LocalDateTime.now()
        return DocumentRow(
            id = id,
            workspaceId = "ws-a",
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
