package com.autodoctree.api.integration

import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.EmbeddingRepository
import com.autodoctree.api.db.OutboxRepository
import com.autodoctree.api.db.PipelineStatusRepository
import com.autodoctree.api.db.UserRepository
import com.autodoctree.api.db.WorkspaceRepository
import com.autodoctree.api.worker.EmbeddingProvider
import com.autodoctree.api.worker.OutboxWorker
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

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
}
