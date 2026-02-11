package com.autodoctree.api.integration

import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.PipelineStatusRepository
import com.autodoctree.api.db.UserRepository
import com.autodoctree.api.db.WorkspaceRepository
import com.autodoctree.api.domain.TreeService
import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "feature.admin-tree-debug=true",
        "tree.structure-worker-enabled=true",
        "structure-worker.enabled=true",
        "structure-worker.base-url=http://127.0.0.1:59999",
        "structure-worker.timeout-ms=150",
        "structure-worker.max-retries=0"
    ]
)
class StructureWorkerFallbackIntegrationTest {

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

    @Test
    fun `rebuild falls back when structure worker is unavailable`() {
        val owner = userRepository.findByEmail("owner@autodoc.local") ?: error("seed owner missing")
        val workspaceId = workspaceRepository.listByUser(owner.id).firstOrNull()?.id ?: error("seed workspace missing")

        createDoc(workspaceId, owner.id, "구조 워커 테스트 A", "회계 결산 승인 보고")
        createDoc(workspaceId, owner.id, "구조 워커 테스트 B", "재무 정산 비용 검토")
        createDoc(workspaceId, owner.id, "구조 워커 테스트 C", "축구 경기 분석 데이터")

        val snapshot = treeService.rebuildWorkspace(workspaceId, owner.id, manual = true)

        assertNotNull(snapshot.id)
        val active = treeService.getActiveTree(
            com.autodoctree.api.tenant.WorkspaceContext(
                userId = owner.id,
                workspaceId = workspaceId,
                role = com.autodoctree.common.Role.OWNER
            )
        )
        assertNotNull(active["snapshot_id"])
    }

    private fun createDoc(workspaceId: String, ownerId: String, title: String, body: String) {
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
    }
}
