package com.autodoctree.api.integration

import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.EmbeddingRepository
import com.autodoctree.api.db.OutboxRepository
import com.autodoctree.api.db.PipelineStatusRepository
import com.autodoctree.api.db.UserRepository
import com.autodoctree.api.db.WorkspaceRepository
import com.autodoctree.api.worker.EmbeddingProvider
import com.autodoctree.api.worker.OutboxWorker
import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

@SpringBootTest
@ActiveProfiles("test")
class EmbeddingTargetsIntegrationTest {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var workspaceRepository: WorkspaceRepository

    @Autowired
    private lateinit var documentRepository: DocumentRepository

    @Autowired
    private lateinit var pipelineStatusRepository: PipelineStatusRepository

    @Autowired
    private lateinit var outboxRepository: OutboxRepository

    @Autowired
    private lateinit var outboxWorker: OutboxWorker

    @Autowired
    private lateinit var embeddingRepository: EmbeddingRepository

    @Autowired
    private lateinit var embeddingProvider: EmbeddingProvider

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var ownerId: String
    private lateinit var workspaceId: String

    @BeforeEach
    fun setup() {
        val owner = userRepository.findByEmail("owner@autodoc.local")
            ?: error("seed owner must exist")
        ownerId = owner.id
        workspaceId = workspaceRepository.listByUser(ownerId).firstOrNull()?.id
            ?: error("seed workspace must exist")
    }

    @Test
    fun `document saved produces channel-separated embedding targets with centroid`() {
        val doc = documentRepository.create(
            workspaceId = workspaceId,
            title = "임베딩 타겟 검증 문서",
            bodyMarkdown = "# 개요\n청구서 결제 내역과 거래 요약\n# 상세\n결제 항목과 합계",
            bodyText = null,
            sourceType = "EDITOR",
            createdBy = ownerId
        )
        pipelineStatusRepository.create(workspaceId, doc.id)

        outboxRepository.insert(
            workspaceId = workspaceId,
            documentId = doc.id,
            eventType = "DocumentSaved",
            payloadJson = objectMapper.writeValueAsString(mapOf("document_id" to doc.id))
        )

        repeat(5) {
            outboxWorker.poll()
        }

        val rows = embeddingRepository
            .listByWorkspaceAndModel(workspaceId, embeddingProvider.modelVersion())
            .filter { it.documentId == doc.id }
        val targetTypes = rows.map { it.targetType.uppercase() }.toSet()

        assertTrue(targetTypes.contains("TITLE"), "TITLE embedding should exist")
        assertTrue(targetTypes.contains("BODY_SUMMARY"), "BODY_SUMMARY embedding should exist")
        assertTrue(targetTypes.contains("SECTION"), "SECTION embedding should exist")
        assertTrue(targetTypes.contains("SECTION_CENTROID"), "SECTION_CENTROID embedding should exist")
    }

    @Test
    fun `stage retry from embed cascades to index and tree`() {
        val doc = documentRepository.create(
            workspaceId = workspaceId,
            title = "재시도 연쇄 검증 문서",
            bodyMarkdown = "# 개요\n임베딩 재시도 후 인덱스/트리까지 자동 진행되어야 한다.",
            bodyText = null,
            sourceType = "EDITOR",
            createdBy = ownerId
        )
        pipelineStatusRepository.create(workspaceId, doc.id)
        pipelineStatusRepository.updateStage(workspaceId, doc.id, Stage.INGEST, StageStatus.DONE, null)
        pipelineStatusRepository.updateStage(workspaceId, doc.id, Stage.EMBED, StageStatus.FAILED, "embedding failed")

        outboxRepository.insert(
            workspaceId = workspaceId,
            documentId = doc.id,
            eventType = "StageRetry",
            payloadJson = objectMapper.writeValueAsString(
                mapOf(
                    "document_id" to doc.id,
                    "stage" to "EMBED"
                )
            )
        )

        repeat(8) {
            outboxWorker.poll()
        }

        val pipeline = pipelineStatusRepository.findByWorkspaceAndDocument(workspaceId, doc.id)
            ?: error("pipeline must exist")

        assertEquals(StageStatus.DONE, pipeline.embedStatus)
        assertEquals(StageStatus.DONE, pipeline.indexStatus)
        assertEquals(StageStatus.DONE, pipeline.treeStatus)
    }

    @Test
    fun `stage retry heals stale embed failed status when stage execution already done`() {
        val doc = documentRepository.create(
            workspaceId = workspaceId,
            title = "임베딩 상태 동기화 검증 문서",
            bodyMarkdown = "# 개요\n기존 실행이 DONE이어도 pipeline_status가 FAILED면 재실행으로 회복되어야 한다.",
            bodyText = null,
            sourceType = "EDITOR",
            createdBy = ownerId
        )
        pipelineStatusRepository.create(workspaceId, doc.id)

        outboxRepository.insert(
            workspaceId = workspaceId,
            documentId = doc.id,
            eventType = "DocumentSaved",
            payloadJson = objectMapper.writeValueAsString(mapOf("document_id" to doc.id))
        )
        repeat(8) {
            outboxWorker.poll()
        }

        val first = pipelineStatusRepository.findByWorkspaceAndDocument(workspaceId, doc.id)
            ?: error("pipeline must exist")
        assertEquals(StageStatus.DONE, first.embedStatus)
        assertEquals(StageStatus.DONE, first.indexStatus)
        assertEquals(StageStatus.DONE, first.treeStatus)

        pipelineStatusRepository.updateStage(workspaceId, doc.id, Stage.EMBED, StageStatus.FAILED, "stale embed failure")

        outboxRepository.insert(
            workspaceId = workspaceId,
            documentId = doc.id,
            eventType = "StageRetry",
            payloadJson = objectMapper.writeValueAsString(
                mapOf(
                    "document_id" to doc.id,
                    "stage" to "EMBED"
                )
            )
        )
        repeat(8) {
            outboxWorker.poll()
        }

        val healed = pipelineStatusRepository.findByWorkspaceAndDocument(workspaceId, doc.id)
            ?: error("pipeline must exist")
        assertEquals(StageStatus.DONE, healed.embedStatus)
        assertEquals(StageStatus.DONE, healed.indexStatus)
        assertEquals(StageStatus.DONE, healed.treeStatus)
    }

    @Test
    fun `stage retry reopens stale running execution for embed`() {
        val doc = documentRepository.create(
            workspaceId = workspaceId,
            title = "임베딩 stale running 복구 문서",
            bodyMarkdown = "# 개요\n오래된 RUNNING 상태를 재실행으로 회복한다.",
            bodyText = null,
            sourceType = "EDITOR",
            createdBy = ownerId
        )
        pipelineStatusRepository.create(workspaceId, doc.id)

        outboxRepository.insert(
            workspaceId = workspaceId,
            documentId = doc.id,
            eventType = "DocumentSaved",
            payloadJson = objectMapper.writeValueAsString(mapOf("document_id" to doc.id))
        )
        repeat(8) {
            outboxWorker.poll()
        }

        pipelineStatusRepository.updateStage(workspaceId, doc.id, Stage.EMBED, StageStatus.FAILED, "stale running")
        jdbcTemplate.update(
            """
            UPDATE stage_execution
            SET status = ?, updated_at = ?
            WHERE workspace_id = ? AND document_id = ? AND stage = ?
            """.trimIndent(),
            StageStatus.RUNNING.name,
            LocalDateTime.now().minusHours(2),
            workspaceId,
            doc.id,
            Stage.EMBED.name
        )

        outboxRepository.insert(
            workspaceId = workspaceId,
            documentId = doc.id,
            eventType = "StageRetry",
            payloadJson = objectMapper.writeValueAsString(
                mapOf(
                    "document_id" to doc.id,
                    "stage" to "EMBED"
                )
            )
        )
        repeat(8) {
            outboxWorker.poll()
        }

        val healed = pipelineStatusRepository.findByWorkspaceAndDocument(workspaceId, doc.id)
            ?: error("pipeline must exist")
        assertEquals(StageStatus.DONE, healed.embedStatus)
        assertEquals(StageStatus.DONE, healed.indexStatus)
        assertEquals(StageStatus.DONE, healed.treeStatus)
    }
}
