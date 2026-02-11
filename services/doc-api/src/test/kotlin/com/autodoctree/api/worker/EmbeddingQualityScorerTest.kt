package com.autodoctree.api.worker

import com.autodoctree.api.db.SectionRow
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class EmbeddingQualityScorerTest {
    private val scorer = EmbeddingQualityScorer(SimpleMeterRegistry())

    @Test
    fun `clean body gets higher q_body than noisy body`() {
        val clean = scorer.score(
            bodyText = "청구서 결제 내역과 거래 일자 정보를 정리한 문서입니다. 고객별 합계와 상세 항목이 포함되어 있습니다.",
            sections = emptyList()
        )
        val noisy = scorer.score(
            bodyText = "%%% ### @@ @@ @@ zzz zzz zzz !!!!! 111 111 ???",
            sections = emptyList()
        )

        assertTrue(clean.qBody > noisy.qBody)
    }

    @Test
    fun `repeated noisy sections reduce q_layout`() {
        val now = LocalDateTime.now()
        val noisySections = List(5) { index ->
            SectionRow(
                id = "s-$index",
                workspaceId = "ws-1",
                documentId = "doc-1",
                ord = index,
                heading = "header",
                chunkText = "###",
                qualityFlags = "GIBBERISH,TOO_SHORT",
                createdAt = now
            )
        }
        val cleanSections = listOf(
            SectionRow("s-a", "ws-1", "doc-1", 0, "개요", "정상 텍스트", null, now),
            SectionRow("s-b", "ws-1", "doc-1", 1, "결과", "정상 텍스트", null, now)
        )

        val noisy = scorer.score("본문", noisySections)
        val clean = scorer.score("본문", cleanSections)

        assertTrue(clean.qLayout > noisy.qLayout)
    }

    @Test
    fun `suspicious OCR-like text reduces q_ocr`() {
        val high = scorer.score("정상 문장과 단어 구성이 있는 보고서 텍스트입니다.", emptyList())
        val low = scorer.score("� � � @@@ ### !!! ### $$$", emptyList())

        assertTrue(high.qOcr > low.qOcr)
    }
}
