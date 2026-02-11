package com.autodoctree.api.domain

import com.autodoctree.api.db.DocumentRow
import org.springframework.stereotype.Component

data class ResolvedUserRule(
    val id: String,
    val ruleType: String,
    val ruleValue: String,
    val targetLabel: String,
    val ruleEffect: String
)

data class UserRuleMatchContext(
    val filenameExtensions: Set<String> = emptySet(),
    val tags: Set<String> = emptySet()
)

@Component
class UserRuleMatcher(
    private val treeLabeler: TreeLabeler
) {
    private val tagRegex = Regex("[#＃]([\\p{L}\\p{N}_-]{2,40})")

    companion object {
        val SUPPORTED_RULE_TYPES = setOf("ENTITY_CONTAINS", "SOURCE_TYPE", "AUTHOR", "FILENAME_EXT", "TAG", "TITLE_CONTAINS")
        val SUPPORTED_RULE_EFFECTS = setOf("HARD", "SOFT")
    }

    fun normalizeRuleValue(value: String): String {
        return value.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    fun normalizeRuleType(value: String): String = value.trim().uppercase()

    fun normalizeRuleEffect(value: String?): String {
        val normalized = value?.trim()?.uppercase().orEmpty()
        return if (normalized in SUPPORTED_RULE_EFFECTS) normalized else "HARD"
    }

    fun extractTags(document: DocumentRow): Set<String> {
        val source = "${document.title} ${document.bodyText ?: ""}"
        return tagRegex.findAll(source)
            .map { it.groupValues.getOrNull(1).orEmpty() }
            .map(::normalizeRuleValue)
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun match(
        document: DocumentRow,
        rules: List<ResolvedUserRule>,
        context: UserRuleMatchContext = UserRuleMatchContext()
    ): ResolvedUserRule? {
        if (rules.isEmpty()) {
            return null
        }
        return rules.firstOrNull { rule -> matches(document, rule, context) }
    }

    fun matchAll(
        document: DocumentRow,
        rules: List<ResolvedUserRule>,
        context: UserRuleMatchContext = UserRuleMatchContext()
    ): List<ResolvedUserRule> {
        if (rules.isEmpty()) {
            return emptyList()
        }
        return rules.filter { rule -> matches(document, rule, context) }
    }

    private fun matches(document: DocumentRow, rule: ResolvedUserRule, context: UserRuleMatchContext): Boolean {
        val title = normalizeRuleValue(document.title)
        val plainText = normalizeRuleValue(document.title + " " + (document.bodyText ?: ""))
        val sourceType = normalizeRuleValue(document.sourceType)
        val author = normalizeRuleValue(document.createdBy)
        val entityTokens = treeLabeler.tokenize(document.title + " " + (document.bodyText ?: ""))
            .map(::normalizeRuleValue)
            .filter { it.isNotBlank() }
            .toSet()
        val tagTokens = (context.tags.ifEmpty { extractTags(document) })
            .map(::normalizeRuleValue)
            .toSet()
        val normalizedRuleValue = when (normalizeRuleType(rule.ruleType)) {
            "FILENAME_EXT" -> normalizeRuleValue(rule.ruleValue).removePrefix(".")
            else -> normalizeRuleValue(rule.ruleValue)
        }
        val filenameExtensions = context.filenameExtensions
            .map { normalizeRuleValue(it).removePrefix(".") }
            .filter { it.isNotBlank() }
            .toSet()

        return when (normalizeRuleType(rule.ruleType)) {
            "TITLE_CONTAINS" -> title.contains(normalizedRuleValue)
            "ENTITY_CONTAINS" -> {
                entityTokens.any { token ->
                    token == normalizedRuleValue ||
                        token.contains(normalizedRuleValue) ||
                        normalizedRuleValue.contains(token)
                } || plainText.contains(normalizedRuleValue)
            }
            "SOURCE_TYPE" -> sourceType == normalizedRuleValue || sourceType.contains(normalizedRuleValue)
            "AUTHOR" -> author.contains(normalizedRuleValue)
            "FILENAME_EXT" -> filenameExtensions.contains(normalizedRuleValue)
            "TAG" -> tagTokens.any { token ->
                token == normalizedRuleValue ||
                    token.contains(normalizedRuleValue) ||
                    normalizedRuleValue.contains(token)
            }
            else -> false
        }
    }
}
