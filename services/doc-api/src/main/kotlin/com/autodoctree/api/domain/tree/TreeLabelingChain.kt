package com.autodoctree.api.domain.tree

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.infra.sha256
import com.autodoctree.api.llm.LlmTextGenerator
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

private const val DEFAULT_LABEL = "general"

data class LabelingResult(
    val labelsByCluster: Map<String, String>,
    val labelCacheBySignature: Map<String, String>,
    val sourceBreakdown: Map<String, Int>
)

@Service
class LabelerChain(
    private val featureFlags: FeatureFlags,
    private val llmLabeler: LlmLabeler,
    private val titlePhraseLabeler: TitlePhraseLabeler,
    private val tfidfLabeler: TfidfLabeler,
    private val treeLabeler: TreeLabeler,
    meterRegistry: MeterRegistry
) {
    private val sourceCounters = mapOf(
        "llm" to meterRegistry.counter("label_chain_total", "source", "llm"),
        "title_phrase" to meterRegistry.counter("label_chain_total", "source", "title_phrase"),
        "tfidf" to meterRegistry.counter("label_chain_total", "source", "tfidf"),
        "cache" to meterRegistry.counter("label_chain_total", "source", "cache"),
        "fallback" to meterRegistry.counter("label_chain_total", "source", "fallback")
    )

    fun labelClusters(
        workspaceDocuments: List<DocumentRow>,
        clusters: List<TreeCluster>,
        existingCache: Map<String, String> = emptyMap()
    ): LabelingResult {
        val docsById = workspaceDocuments.associateBy { it.id }
        val tfidfLabels = if (featureFlags.tfidfLabelerFallback) {
            tfidfLabeler.labelClusters(workspaceDocuments, clusters)
        } else {
            emptyMap()
        }

        val labelsByCluster = mutableMapOf<String, String>()
        val labelCache = existingCache.toMutableMap()
        val sourceBreakdown = mutableMapOf(
            "llm" to 0,
            "title_phrase" to 0,
            "tfidf" to 0,
            "cache" to 0,
            "fallback" to 0
        )

        clusters.forEach { cluster ->
            val clusterDocs = cluster.documentIds.mapNotNull { docsById[it] }
            val fallbackLabel = treeLabeler.fallbackLabelFor(clusterDocs).ifBlank { DEFAULT_LABEL }
            val signature = clusterSignature(clusterDocs)

            val (candidate, source) = when {
                !labelCache[signature].isNullOrBlank() -> labelCache.getValue(signature) to "cache"
                else -> {
                    val llm = llmLabeler.labelCluster(clusterDocs, fallbackLabel)
                    val titlePhrase = titlePhraseLabeler.labelCluster(clusterDocs, treeLabeler)
                    when {
                        !llm.isNullOrBlank() -> llm to "llm"
                        !titlePhrase.isNullOrBlank() -> titlePhrase to "title_phrase"
                        !tfidfLabels[cluster.id].isNullOrBlank() -> tfidfLabels.getValue(cluster.id) to "tfidf"
                        else -> fallbackLabel to "fallback"
                    }
                }
            }

            val normalized = treeLabeler.finalizeLabel(
                candidate = candidate,
                fallbackLabel = fallbackLabel,
                clusterQualityScore = cluster.qualityScore,
                clusterSize = clusterDocs.size,
                usedPhrase = candidate.contains('-') || candidate.contains(' ')
            ).ifBlank { DEFAULT_LABEL }

            labelsByCluster[cluster.id] = normalized
            labelCache[signature] = normalized
            sourceBreakdown[source] = (sourceBreakdown[source] ?: 0) + 1
            sourceCounters[source]?.increment()
        }

        return LabelingResult(
            labelsByCluster = labelsByCluster,
            labelCacheBySignature = labelCache,
            sourceBreakdown = sourceBreakdown
        )
    }

    private fun clusterSignature(clusterDocs: List<DocumentRow>): String {
        val base = clusterDocs
            .sortedBy { it.id }
            .joinToString("|") { doc ->
                val titleTokens = treeLabeler.tokenize(doc.title).take(6).joinToString(",")
                val heading = doc.title.take(80)
                "${doc.id}:$heading:$titleTokens"
            }
        return sha256(base)
    }
}

@Service
class TfidfLabeler(
    private val treeLabeler: TreeLabeler
) {
    fun labelClusters(workspaceDocuments: List<DocumentRow>, clusters: List<TreeCluster>): Map<String, String> {
        return treeLabeler.labelClusters(workspaceDocuments, clusters)
    }
}

@Service
class TitlePhraseLabeler {
    fun labelCluster(clusterDocs: List<DocumentRow>, treeLabeler: TreeLabeler): String? {
        if (clusterDocs.isEmpty()) {
            return null
        }

        val phraseFrequency = mutableMapOf<String, Int>()
        val tokenFrequency = mutableMapOf<String, Int>()
        clusterDocs.forEach { doc ->
            val titleTokens = treeLabeler.tokenize(doc.title)
            titleTokens.forEach { token ->
                if (token.contains('-') && token.length in 3..24) {
                    phraseFrequency[token] = (phraseFrequency[token] ?: 0) + 1
                }
                if (!token.contains('-') && token.length in 2..16) {
                    tokenFrequency[token] = (tokenFrequency[token] ?: 0) + 1
                }
            }
        }

        val phraseCandidate = phraseFrequency.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull { (phrase, count) ->
                val coverage = count.toDouble() / clusterDocs.size.toDouble().coerceAtLeast(1.0)
                coverage >= 0.5 && phrase.split('-').size in 2..4
            }
            ?.key

        if (!phraseCandidate.isNullOrBlank()) {
            return phraseCandidate
        }

        val topTokens = tokenFrequency.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(2)

        if (topTokens.isEmpty()) {
            return null
        }
        return topTokens.joinToString("-")
    }
}

@Service
class LlmLabeler(
    private val featureFlags: FeatureFlags,
    private val llmTextGenerator: LlmTextGenerator,
    private val promptTemplateLoader: PromptTemplateLoader,
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val successCounter = meterRegistry.counter("llm_labeler_total", "result", "success")
    private val failureCounter = meterRegistry.counter("llm_labeler_total", "result", "failure")

    fun labelCluster(clusterDocs: List<DocumentRow>, fallbackLabel: String): String? {
        if (!featureFlags.llmLabeling || clusterDocs.isEmpty() || llmTextGenerator.providerId() == "none") {
            return null
        }

        val sampleDocs = clusterDocs
            .sortedWith(compareByDescending<DocumentRow> { it.title.length }.thenBy { it.id })
            .take(3)

        val snippets = sampleDocs.joinToString("\n") { doc ->
            val excerpt = sanitizeExcerpt(doc.bodyText ?: doc.bodyMarkdown ?: doc.title)
            "- title: ${doc.title.take(80)}\n  excerpt: ${excerpt.take(180)}"
        }

        val prompt = promptTemplateLoader.load("prompts/label_v1.txt")
            .replace("{{DOCUMENT_SNIPPETS}}", snippets)
            .replace("{{FALLBACK_LABEL}}", fallbackLabel)

        return runCatching {
            llmTextGenerator.generate(prompt)
                .lineSequence()
                .firstOrNull()
                ?.trim()
                ?.trim('"', '\'', '`')
                ?.replace(Regex("^(라벨|label)\\s*[:：]\\s*"), "")
                ?.take(32)
                ?.ifBlank { null }
        }.onSuccess {
            if (!it.isNullOrBlank()) {
                successCounter.increment()
            } else {
                failureCounter.increment()
            }
        }.onFailure { ex ->
            failureCounter.increment()
            logger.warn("llm_labeler_failed doc_count={} message={}", clusterDocs.size, ex.message)
        }.getOrNull()
    }

    private fun sanitizeExcerpt(raw: String): String {
        return raw
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "(empty)" }
    }
}

@Service
class LlmExplainGenerator(
    private val featureFlags: FeatureFlags,
    private val llmTextGenerator: LlmTextGenerator,
    private val promptTemplateLoader: PromptTemplateLoader,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val successCounter = meterRegistry.counter("llm_explain_total", "result", "success")
    private val failureCounter = meterRegistry.counter("llm_explain_total", "result", "failure")

    fun generate(
        keywords: List<String>,
        similarDocs: List<Map<String, Any?>>,
        signals: List<String>
    ): String? {
        if (!featureFlags.llmExplain || llmTextGenerator.providerId() == "none") {
            return null
        }

        val signalPayload = mapOf(
            "keywords" to keywords.take(5),
            "similar_docs" to similarDocs.take(3).map { mapOf(
                "document_id" to (it["document_id"] ?: ""),
                "similarity" to (it["similarity"] ?: 0.0)
            ) },
            "signals" to signals.take(5)
        )

        val prompt = promptTemplateLoader.load("prompts/explain_v1.txt")
            .replace("{{SIGNALS_JSON}}", objectMapper.writeValueAsString(signalPayload))

        return runCatching {
            llmTextGenerator.generate(prompt)
                .replace(Regex("\\s+"), " ")
                .trim()
                .lineSequence()
                .firstOrNull()
                ?.take(140)
                ?.ifBlank { null }
        }.onSuccess {
            if (!it.isNullOrBlank()) {
                successCounter.increment()
            } else {
                failureCounter.increment()
            }
        }.onFailure { ex ->
            failureCounter.increment()
            logger.warn("llm_explain_failed signals_count={} message={}", signals.size, ex.message)
        }.getOrNull()
    }
}

@Component
class PromptTemplateLoader {
    private val cache = mutableMapOf<String, String>()

    fun load(path: String): String {
        return cache.getOrPut(path) {
            val resource = ClassPathResource(path)
            resource.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}
