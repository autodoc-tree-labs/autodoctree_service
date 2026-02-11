package com.autodoctree.api.integration

import com.autodoctree.api.db.ConceptPrototypeRepository
import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.EmbeddingRepository
import com.autodoctree.api.db.PipelineStatusRepository
import com.autodoctree.api.db.UserRepository
import com.autodoctree.api.db.WorkspaceRepository
import com.autodoctree.api.domain.TreeService
import com.autodoctree.api.worker.EmbeddingProvider
import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "tree.concept-enabled=true",
        "tree.concept-assign-threshold=0.40",
        "tree.concept-min-docs=1",
        "tree.concept-update-alpha=0.35",
        "tree.assign-quarantine-enabled=false",
        "tree.optimizer-enabled=true",
        "tree.optimizer-max-iterations=3",
        "tree.optimizer-change-cost-lambda=0.85",
        "tree.optimizer-cannot-violation-mu=3.0",
        "tree.optimizer-size-penalty-nu=0.12",
        "tree.optimizer-min-improvement=0.005"
    ]
)
class ConceptPrototypeIncrementalIntegrationTest {

    @Autowired
    private lateinit var treeService: TreeService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var workspaceRepository: WorkspaceRepository

    @Autowired
    private lateinit var documentRepository: DocumentRepository

    @Autowired
    private lateinit var pipelineStatusRepository: PipelineStatusRepository

    @Autowired
    private lateinit var conceptPrototypeRepository: ConceptPrototypeRepository

    @Autowired
    private lateinit var embeddingRepository: EmbeddingRepository

    @Autowired
    private lateinit var embeddingProvider: EmbeddingProvider

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `incremental rebuild records concept preassign and optimizer objective`() {
        val owner = userRepository.findByEmail("owner@autodoc.local") ?: error("seed owner missing")
        val workspaceId = workspaceRepository.listByUser(owner.id).firstOrNull()?.id ?: error("seed workspace missing")

        val seedSuffix = System.currentTimeMillis().toString().takeLast(6)
        val financeA = createDoc(workspaceId, owner.id, "회계 결산 보고서 $seedSuffix", "회계 정산 승인 비용 내역 분석")
        val financeB = createDoc(workspaceId, owner.id, "재무 승인 이력 $seedSuffix", "회계 비용 승인 결산 점검")
        val sportsA = createDoc(workspaceId, owner.id, "축구 경기 리포트 $seedSuffix", "축구 경기 기록 전술 하이라이트")
        val sportsB = createDoc(workspaceId, owner.id, "야구 경기 분석 $seedSuffix", "야구 경기 득점 기록 선수 분석")
        upsertEmbedding(workspaceId, financeA, listOf(1.0, 0.0, 0.0, 0.0))
        upsertEmbedding(workspaceId, financeB, listOf(0.96, 0.04, 0.0, 0.0))
        upsertEmbedding(workspaceId, sportsA, listOf(0.0, 1.0, 0.0, 0.0))
        upsertEmbedding(workspaceId, sportsB, listOf(0.0, 0.96, 0.04, 0.0))

        val initial = treeService.rebuildWorkspace(workspaceId, owner.id, manual = true)
        assertNotNull(initial.id)

        val initialConcepts = conceptPrototypeRepository.listByWorkspaceAndActiveSnapshot(workspaceId)
        assertTrue(initialConcepts.isNotEmpty(), "expected active concept prototypes after initial rebuild")

        val financeNew = createDoc(workspaceId, owner.id, "회계 비용 점검 신규 $seedSuffix", "회계 승인 결산 비용 검토 정산")
        upsertEmbedding(workspaceId, financeNew, listOf(0.94, 0.05, 0.01, 0.0))

        val incremental = treeService.rebuildWorkspace(workspaceId, owner.id, manual = true)
        val debug = treeService.debugRebuild(workspaceId, incremental.id)

        @Suppress("UNCHECKED_CAST")
        val stageLogs = debug["stage_logs"] as List<Map<String, Any?>>
        val conceptStage = stageLogs.firstOrNull { it["stage"] == "concept_preassign" }
        assertNotNull(conceptStage, "expected concept_preassign stage in rebuild trace")

        @Suppress("UNCHECKED_CAST")
        val conceptDetails = conceptStage?.get("details") as Map<String, Any?>
        val conceptAssignedCount = (conceptDetails["assigned_doc_count"] as Number).toInt()
        assertTrue(conceptAssignedCount >= 1, "expected at least one concept preassigned document")

        @Suppress("UNCHECKED_CAST")
        val decisionSummary = debug["decision_summary"] as Map<String, Any?>
        val objectiveScore = decisionSummary["objective_score"] as? Number
        val changeCost = decisionSummary["change_cost"] as? Number
        assertNotNull(objectiveScore, "expected objective_score in decision summary")
        assertNotNull(changeCost, "expected change_cost in decision summary")
    }

    private fun createDoc(workspaceId: String, ownerId: String, title: String, body: String): String {
        val doc = documentRepository.create(
            workspaceId = workspaceId,
            title = title,
            bodyMarkdown = body,
            bodyText = body,
            sourceType = "EDITOR",
            createdBy = ownerId
        )
        pipelineStatusRepository.create(workspaceId, doc.id)
        pipelineStatusRepository.updateStage(workspaceId, doc.id, Stage.INGEST, StageStatus.DONE, null)
        pipelineStatusRepository.updateStage(workspaceId, doc.id, Stage.EMBED, StageStatus.DONE, null)
        pipelineStatusRepository.updateStage(workspaceId, doc.id, Stage.INDEX, StageStatus.DONE, null)
        pipelineStatusRepository.updateStage(workspaceId, doc.id, Stage.TREE, StageStatus.DONE, null)
        return doc.id
    }

    private fun upsertEmbedding(workspaceId: String, documentId: String, vector: List<Double>) {
        val modelVersion = embeddingProvider.modelVersion()
        val vectorJson = objectMapper.writeValueAsString(vector)
        embeddingRepository.upsert(
            workspaceId = workspaceId,
            documentId = documentId,
            targetType = "DOCUMENT",
            targetId = documentId,
            inputHash = "itest-$documentId-$modelVersion",
            vectorJson = vectorJson,
            modelVersion = modelVersion
        )
    }
}
