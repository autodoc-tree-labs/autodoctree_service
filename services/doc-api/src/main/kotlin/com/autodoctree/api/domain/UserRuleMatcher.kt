package com.autodoctree.api.domain

import com.autodoctree.api.db.DocumentRow
import org.springframework.stereotype.Component

data class ResolvedUserRule(
    val id: String,
    val ruleType: String,
    val ruleValue: String,
    val targetLabel: String
)

@Component
class UserRuleMatcher(
    private val treeLabeler: TreeLabeler
) {
    fun normalizeRuleValue(value: String): String {
        return value.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    fun match(document: DocumentRow, rules: List<ResolvedUserRule>): ResolvedUserRule? {
        if (rules.isEmpty()) {
            return null
        }
        val title = normalizeRuleValue(document.title)
        val plainText = normalizeRuleValue(document.title + " " + (document.bodyText ?: ""))
        val entityTokens = treeLabeler.tokenize(document.title + " " + (document.bodyText ?: ""))
            .map(::normalizeRuleValue)
            .filter { it.isNotBlank() }
            .toSet()

        return rules.firstOrNull { rule ->
            when (rule.ruleType) {
                "TITLE_CONTAINS" -> title.contains(rule.ruleValue)
                "ENTITY_CONTAINS" -> {
                    entityTokens.any { token ->
                        token == rule.ruleValue || token.contains(rule.ruleValue) || rule.ruleValue.contains(token)
                    } || plainText.contains(rule.ruleValue)
                }
                else -> false
            }
        }
    }
}
