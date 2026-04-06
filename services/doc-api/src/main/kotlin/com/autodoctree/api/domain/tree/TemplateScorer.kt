package com.autodoctree.api.domain.tree

import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.SectionRow
import org.springframework.stereotype.Service

@Service
class TemplateScorer(
    private val treeProperties: TreeProperties
) {

    data class TemplateSignal(
        val score: Double,
        val boilerplateRatio: Double,
        val repeatedNgramRatio: Double,
        val noisySectionRatio: Double,
        val candidate: Boolean,
        val shouldQuarantine: Boolean,
        val reasons: List<String>
    ) {
        fun toExplainMap(): Map<String, Any?> {
            return mapOf(
                "score" to score,
                "boilerplate_ratio" to boilerplateRatio,
                "repeated_ngram_ratio" to repeatedNgramRatio,
                "noisy_section_ratio" to noisySectionRatio,
                "candidate" to candidate,
                "should_quarantine" to shouldQuarantine,
                "reasons" to reasons
            )
        }
    }

    fun scoreDocuments(
        documents: List<DocumentRow>,
        sectionsByDocument: Map<String, List<SectionRow>>
    ): Map<String, TemplateSignal> {
        if (documents.isEmpty()) {
            return emptyMap()
        }

        val fingerprintFrequency = buildFingerprintFrequency(documents, sectionsByDocument)

        return documents.associate { document ->
            val sections = sectionsByDocument[document.id].orEmpty()
            val boilerplateRatio = computeBoilerplateRatio(sections, fingerprintFrequency)
            val repeatedNgramRatio = computeRepeatedNgramRatio(document)
            val noisySectionRatio = computeNoisySectionRatio(sections)

            val score = (
                (boilerplateRatio * 0.50) +
                    (repeatedNgramRatio * 0.35) +
                    (noisySectionRatio * 0.15)
                ).coerceIn(0.0, 1.0)

            val reasons = mutableListOf<String>()
            if (boilerplateRatio >= treeProperties.templateBoilerplateRatioThreshold) {
                reasons += "BOILERPLATE_RATIO"
            }
            if (repeatedNgramRatio >= treeProperties.templateNgramRepeatThreshold) {
                reasons += "REPEATED_NGRAM"
            }
            if (score >= treeProperties.templateScoreThreshold) {
                reasons += "SCORE_THRESHOLD"
            }
            if (noisySectionRatio >= 0.60) {
                reasons += "NOISY_SECTION_RATIO"
            }

            val candidate = reasons.any { reason ->
                reason == "BOILERPLATE_RATIO" || reason == "REPEATED_NGRAM" || reason == "SCORE_THRESHOLD"
            }

            document.id to TemplateSignal(
                score = score,
                boilerplateRatio = boilerplateRatio,
                repeatedNgramRatio = repeatedNgramRatio,
                noisySectionRatio = noisySectionRatio,
                candidate = candidate,
                shouldQuarantine = treeProperties.templateIsolationEnabled && candidate,
                reasons = reasons
            )
        }
    }

    private fun buildFingerprintFrequency(
        documents: List<DocumentRow>,
        sectionsByDocument: Map<String, List<SectionRow>>
    ): Map<String, Int> {
        if (documents.isEmpty()) {
            return emptyMap()
        }
        val docIdsByFingerprint = mutableMapOf<String, MutableSet<String>>()
        documents.forEach { document ->
            val fingerprints = sectionsByDocument[document.id]
                .orEmpty()
                .mapNotNull { section -> normalizeFingerprint(section.chunkText) }
                .toSet()
            fingerprints.forEach { fingerprint ->
                val holder = docIdsByFingerprint.getOrPut(fingerprint) { mutableSetOf() }
                holder += document.id
            }
        }
        return docIdsByFingerprint.mapValues { (_, docIds) -> docIds.size }
    }

    private fun computeBoilerplateRatio(
        sections: List<SectionRow>,
        fingerprintFrequency: Map<String, Int>
    ): Double {
        if (sections.isEmpty()) {
            return 0.0
        }
        val repeatedSections = sections.count { section ->
            val fingerprint = normalizeFingerprint(section.chunkText) ?: return@count false
            (fingerprintFrequency[fingerprint] ?: 0) >= treeProperties.templateFingerprintMinDocs
        }
        return (repeatedSections.toDouble() / sections.size.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun computeRepeatedNgramRatio(document: DocumentRow): Double {
        val text = "${document.title} ${document.bodyText ?: document.bodyMarkdown.orEmpty()}"
        val tokens = tokenize(text)
        if (tokens.size < 6) {
            return 0.0
        }
        val tri = repeatedRatio(tokens, 3)
        val four = repeatedRatio(tokens, 4)
        return ((tri * 0.6) + (four * 0.4)).coerceIn(0.0, 1.0)
    }

    private fun repeatedRatio(tokens: List<String>, n: Int): Double {
        if (tokens.size < n) {
            return 0.0
        }
        val ngrams = (0..tokens.size - n).map { index ->
            tokens.subList(index, index + n).joinToString(" ")
        }
        val counts = ngrams.groupingBy { value -> value }.eachCount()
        val repeatedMass = counts.values
            .filter { count -> count >= 2 }
            .sumOf { count -> count - 1 }
        return (repeatedMass.toDouble() / ngrams.size.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun computeNoisySectionRatio(sections: List<SectionRow>): Double {
        if (sections.isEmpty()) {
            return 0.0
        }
        val noisySections = sections.count { section ->
            section.qualityFlags
                ?.split(',')
                ?.map { flag -> flag.trim().uppercase() }
                ?.any { flag -> flag in setOf("GIBBERISH", "TOO_SHORT", "ZERO_LENGTH") }
                ?: false
        }
        return (noisySections.toDouble() / sections.size.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun normalizeFingerprint(raw: String): String? {
        val normalized = raw
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length < 40) {
            return null
        }
        return normalized.take(240)
    }

    private fun tokenize(text: String): List<String> {
        return Regex("[\\p{L}\\p{N}]{2,}")
            .findAll(text.lowercase())
            .map { match -> match.value }
            .toList()
    }
}
