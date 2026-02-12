package com.autodoctree.api.domain

import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.SectionRow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TemplateScorerTest {

    @Test
    fun `repeated ngram heavy document is detected as template candidate`() {
        val scorer = TemplateScorer(testTreeProperties(templateIsolationEnabled = true))
        val repeatedPhrase = List(40) { "approval request form" }.joinToString(" ")
        val doc = document("doc-template", repeatedPhrase)

        val scored = scorer.scoreDocuments(
            documents = listOf(doc),
            sectionsByDocument = emptyMap()
        )

        val signal = scored.getValue(doc.id)
        assertTrue(signal.repeatedNgramRatio >= 0.18)
        assertTrue(signal.candidate)
        assertTrue(signal.shouldQuarantine)
    }

    @Test
    fun `boilerplate ratio uses repeated section fingerprints across documents`() {
        val scorer = TemplateScorer(
            testTreeProperties(
                templateIsolationEnabled = true,
                templateFingerprintMinDocs = 2,
                templateBoilerplateRatioThreshold = 0.50
            )
        )

        val docs = listOf(
            document("doc-a", "invoice form A"),
            document("doc-b", "invoice form B"),
            document("doc-c", "invoice form C")
        )

        val repeatedHeader = "This document follows standard corporate invoice header and compliance text section."
        val repeatedFooter = "This footer line is repeated for every invoice and should be treated as boilerplate text."

        val sectionsByDocument = mapOf(
            "doc-a" to listOf(
                section("doc-a", 0, repeatedHeader),
                section("doc-a", 1, "Project specific detail A with unique payload."),
                section("doc-a", 2, repeatedFooter)
            ),
            "doc-b" to listOf(
                section("doc-b", 0, repeatedHeader),
                section("doc-b", 1, "Project specific detail B with unique payload."),
                section("doc-b", 2, repeatedFooter)
            ),
            "doc-c" to listOf(
                section("doc-c", 0, repeatedHeader),
                section("doc-c", 1, "Project specific detail C with unique payload."),
                section("doc-c", 2, repeatedFooter)
            )
        )

        val scored = scorer.scoreDocuments(docs, sectionsByDocument)
        val signal = scored.getValue("doc-a")

        assertTrue(signal.boilerplateRatio >= 0.66)
        assertTrue(signal.candidate)
        assertTrue(signal.shouldQuarantine)
    }

    @Test
    fun `isolation disabled keeps candidate from quarantine`() {
        val scorer = TemplateScorer(testTreeProperties(templateIsolationEnabled = false))
        val repeatedPhrase = List(35) { "standard template footer" }.joinToString(" ")
        val doc = document("doc-template", repeatedPhrase)

        val scored = scorer.scoreDocuments(
            documents = listOf(doc),
            sectionsByDocument = emptyMap()
        )

        val signal = scored.getValue(doc.id)
        assertTrue(signal.candidate)
        assertFalse(signal.shouldQuarantine)
    }

    private fun testTreeProperties(
        templateIsolationEnabled: Boolean,
        templateBoilerplateRatioThreshold: Double = 0.50,
        templateFingerprintMinDocs: Int = 3
    ): TreeProperties {
        return TreeProperties(
            neighborTopK = 3,
            neighborMinSimilarity = 0.0,
            neighborNormalize = true,
            templateIsolationEnabled = templateIsolationEnabled,
            templateScoreThreshold = 0.64,
            templateBoilerplateRatioThreshold = templateBoilerplateRatioThreshold,
            templateNgramRepeatThreshold = 0.18,
            templateFingerprintMinDocs = templateFingerprintMinDocs,
            maxClusterSize = 20,
            minClusterSize = 2,
            communityResolution = 1.0,
            personalizationDecay = 0.85,
            personalizationMinScore = 1.2,
            fusionSemanticWeight = 0.8,
            fusionLexicalWeight = 0.2,
            fusionLexicalGate = 0.35,
            otherClusterScoreThreshold = 0.32
        )
    }

    private fun document(id: String, body: String): DocumentRow {
        val now = LocalDateTime.now()
        return DocumentRow(
            id = id,
            workspaceId = "ws-a",
            title = "template $id",
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

    private fun section(documentId: String, ord: Int, chunk: String): SectionRow {
        return SectionRow(
            id = "$documentId-$ord",
            workspaceId = "ws-a",
            documentId = documentId,
            ord = ord,
            heading = null,
            chunkText = chunk,
            qualityFlags = null,
            createdAt = LocalDateTime.now()
        )
    }
}
