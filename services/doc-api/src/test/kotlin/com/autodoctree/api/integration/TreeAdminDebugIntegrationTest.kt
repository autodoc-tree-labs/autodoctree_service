package com.autodoctree.api.integration

import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.FeedbackRepository
import com.autodoctree.api.db.PipelineStatusRepository
import com.autodoctree.api.db.TreeRepository
import com.autodoctree.api.db.UserRepository
import com.autodoctree.api.db.WorkspaceRepository
import com.autodoctree.api.domain.TreeService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "feature.user-rules-v1=true",
        "feature.feedback-routing-v2=true",
        "feature.admin-tree-debug=true"
    ]
)
class TreeAdminDebugIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var workspaceRepository: WorkspaceRepository

    @Autowired
    private lateinit var documentRepository: DocumentRepository

    @Autowired
    private lateinit var pipelineStatusRepository: PipelineStatusRepository

    @Autowired
    private lateinit var treeRepository: TreeRepository

    @Autowired
    private lateinit var treeService: TreeService

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    private lateinit var ownerId: String
    private lateinit var workspaceId: String
    private lateinit var token: String
    private lateinit var memberToken: String
    private lateinit var debugDocId: String

    @BeforeEach
    fun setup() {
        val owner = userRepository.findByEmail("owner@autodoc.local")
            ?: error("seed owner must exist")
        ownerId = owner.id
        workspaceId = workspaceRepository.listByUser(ownerId).firstOrNull()?.id
            ?: error("seed workspace must exist")
        token = login("owner@autodoc.local", "password")
        memberToken = login("member@autodoc.local", "password")

        val anchor = createDoc("관리자 디버그 문서 A", "과학 연구와 실험 분석 데이터")
        createDoc("관리자 디버그 문서 B", "과학 실험 노트와 연구 계획 데이터")
        createDoc("관리자 디버그 문서 C", "과학 데이터 분석 실험 기록")
        debugDocId = anchor.id
        treeService.rebuildWorkspace(workspaceId, ownerId, manual = true)
    }

    @Test
    fun `admin debug neighbors returns signal breakdown contract`() {
        mockMvc.perform(
            get("/api/v1/admin/tree/debug/neighbors")
                .param("document_id", debugDocId)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.document_id").value(debugDocId))
            .andExpect(jsonPath("$.neighbors").isArray)
            .andExpect(jsonPath("$.neighbors[0].neighbor_doc_id").exists())
            .andExpect(jsonPath("$.neighbors[0].title").exists())
            .andExpect(jsonPath("$.neighbors[0].lex_sim").exists())
            .andExpect(jsonPath("$.neighbors[0].entity_overlap").exists())
            .andExpect(jsonPath("$.neighbors[0].final_sim").exists())
            .andExpect(jsonPath("$.neighbors[0].gate_flags.lexical_gate_passed").exists())
    }

    @Test
    fun `admin debug doc endpoint returns masked evidence contract`() {
        mockMvc.perform(
            get("/api/v1/admin/tree/debug/docs/$debugDocId")
                .param("top_n", "5")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.document_id").value(debugDocId))
            .andExpect(jsonPath("$.title").doesNotExist())
            .andExpect(jsonPath("$.title_mask.hash").exists())
            .andExpect(jsonPath("$.title_mask.length").exists())
            .andExpect(jsonPath("$.assignment.node_id").exists())
            .andExpect(jsonPath("$.assignment_confidence").exists())
            .andExpect(jsonPath("$.neighbors[0].neighbor_doc_id").exists())
            .andExpect(jsonPath("$.neighbors[0].channel_scores.final").exists())
            .andExpect(jsonPath("$.neighbors[0].edge_decision.reason").exists())
    }

    @Test
    fun `document explain endpoint returns minimal evidence contract`() {
        mockMvc.perform(
            get("/api/v1/documents/$debugDocId/explain")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.document_id").value(debugDocId))
            .andExpect(jsonPath("$.rationale.evidence.neighbors").isArray)
            .andExpect(jsonPath("$.rationale.evidence.reason_codes").isArray)
            .andExpect(jsonPath("$.rationale.llm_sentence").exists())
            .andExpect(jsonPath("$.rationale.body_text").doesNotExist())
    }

    @Test
    fun `accept explain writes feedback event`() {
        mockMvc.perform(
            post("/api/v1/documents/$debugDocId/explain/accept")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isNoContent)

        val accepted = feedbackRepository.listByWorkspace(workspaceId, 50).any { event ->
            event.eventType == "EXPLAIN_ACCEPT" &&
                objectMapper.readTree(event.payloadJson).path("document_id").asText() == debugDocId
        }
        assertTrue(accepted, "Expected EXPLAIN_ACCEPT feedback event for doc=$debugDocId")
    }

    @Test
    fun `tree active returns placement metadata for unsorted workflow`() {
        val activeTree = mockMvc.perform(
            get("/api/v1/tree/active")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val root = objectMapper.readTree(activeTree)
        val node = findNodeContainingDoc(root, debugDocId)
        val summary = node?.path("document_summaries")?.firstOrNull { item ->
            item.path("id").asText() == debugDocId
        }
        assertTrue(summary != null, "Expected document summary for doc=$debugDocId")
        assertTrue(summary!!.has("quarantine_reason"))
        assertTrue(summary.has("placement_confidence"))
        assertTrue(summary.path("placement_candidates").isArray)
    }

    @Test
    fun `feedback move accepts source and stores analytics payload`() {
        val active = treeRepository.findActiveSnapshot(workspaceId) ?: error("active snapshot missing")
        val membership = treeRepository.findMembershipByDocInSnapshot(workspaceId, active.id, debugDocId)
            ?: error("debug document membership missing")

        mockMvc.perform(
            post("/api/v1/feedback/move")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "document_id" to debugDocId,
                            "from_node_id" to membership.nodeId,
                            "to_node_id" to membership.nodeId,
                            "source" to "QUICK_CONFIRM"
                        )
                    )
                )
        ).andExpect(status().isNoContent)

        val payloadStored = feedbackRepository.listByWorkspace(workspaceId, 50).any { event ->
            event.eventType == "MOVE" &&
                objectMapper.readTree(event.payloadJson).path("document_id").asText() == debugDocId &&
                objectMapper.readTree(event.payloadJson).path("source").asText() == "QUICK_CONFIRM"
        }
        assertTrue(payloadStored, "Expected MOVE feedback payload to include source=QUICK_CONFIRM")
    }

    @Test
    fun `admin debug cluster endpoint returns members and exemplars`() {
        val active = treeRepository.findActiveSnapshot(workspaceId) ?: error("active snapshot missing")
        val membership = treeRepository.findMembershipByDocInSnapshot(workspaceId, active.id, debugDocId)
            ?: error("debug document membership missing")

        mockMvc.perform(
            get("/api/v1/admin/tree/debug/clusters/${membership.nodeId}")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.cluster_id").value(membership.nodeId))
            .andExpect(jsonPath("$.member_count").isNumber)
            .andExpect(jsonPath("$.members").isArray)
            .andExpect(jsonPath("$.members[0].document_id").exists())
            .andExpect(jsonPath("$.members[0].title_mask.hash").exists())
            .andExpect(jsonPath("$.exemplars").isArray)
            .andExpect(jsonPath("$.label_candidates").isArray)
    }

    @Test
    fun `admin debug rebuild endpoint returns parameter snapshot`() {
        val active = treeRepository.findActiveSnapshot(workspaceId) ?: error("active snapshot missing")

        mockMvc.perform(
            get("/api/v1/admin/tree/debug/rebuilds/${active.id}")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.snapshot_id").value(active.id))
            .andExpect(jsonPath("$.parameters.neighbor_top_k").exists())
            .andExpect(jsonPath("$.models.embedding_model").exists())
            .andExpect(jsonPath("$.decision_summary.status").exists())
            .andExpect(jsonPath("$.stage_logs").isArray)
    }

    @Test
    fun `admin can update tree policy and rebuild debug reflects override`() {
        mockMvc.perform(
            patch("/api/v1/admin/tree/policy")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "auto_threshold" to 0.95,
                            "recommend_threshold" to 0.80,
                            "quarantine_enabled" to true,
                            "reranker_enabled" to false
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.source").value("OVERRIDE"))
            .andExpect(jsonPath("$.auto_threshold").value(0.95))
            .andExpect(jsonPath("$.recommend_threshold").value(0.8))

        treeService.rebuildWorkspace(workspaceId, ownerId, manual = true)
        val active = treeRepository.findActiveSnapshot(workspaceId) ?: error("active snapshot missing")
        mockMvc.perform(
            get("/api/v1/admin/tree/debug/rebuilds/${active.id}")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.decision_summary.auto_ratio").exists())
            .andExpect(jsonPath("$.decision_summary.recommend_ratio").exists())
            .andExpect(jsonPath("$.decision_summary.policy_threshold.auto").value(0.95))
            .andExpect(jsonPath("$.decision_summary.policy_threshold.recommend").value(0.8))
    }

    @Test
    fun `member role cannot update tree policy`() {
        mockMvc.perform(
            patch("/api/v1/admin/tree/policy")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $memberToken")
                .header("X-Workspace-Id", workspaceId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "auto_threshold" to 0.85,
                            "recommend_threshold" to 0.65,
                            "quarantine_enabled" to true,
                            "reranker_enabled" to false
                        )
                    )
                )
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `member role cannot access admin debug endpoint`() {
        mockMvc.perform(
            get("/api/v1/admin/tree/debug/neighbors")
                .param("document_id", debugDocId)
                .header("Authorization", "Bearer $memberToken")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/admin/tree/debug/docs/$debugDocId")
                .header("Authorization", "Bearer $memberToken")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `user rule forces target label on next rebuild`() {
        val active = treeRepository.findActiveSnapshot(workspaceId) ?: error("active snapshot missing")
        val targetNode = treeRepository.listNodes(workspaceId, active.id).firstOrNull { it.depth >= 1 && it.label != "AutoDoc" }
            ?: error("target node missing")
        val uniqueKeyword = "규칙강제키워드"
        val forcedDoc = createDoc("$uniqueKeyword 문서", "규칙 강제 라우팅 확인")

        mockMvc.perform(
            post("/api/v1/admin/tree/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "rule_type" to "TITLE_CONTAINS",
                            "rule_value" to uniqueKeyword,
                            "node_id" to targetNode.id
                        )
                    )
                )
        ).andExpect(status().isOk)

        treeService.rebuildWorkspace(workspaceId, ownerId, manual = true)

        val activeTree = mockMvc.perform(
            get("/api/v1/tree/active")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val root = objectMapper.readTree(activeTree)
        val forcedNode = findNodeContainingDoc(root, forcedDoc.id)
        assertTrue(
            forcedNode?.path("label")?.asText() == targetNode.label,
            "Expected forced document to land on label='${targetNode.label}', but got node=$forcedNode"
        )
    }

    @Test
    fun `locked node keeps label and parent label across rebuild`() {
        val beforeSnapshot = treeRepository.findActiveSnapshot(workspaceId) ?: error("active snapshot missing")
        val beforeNodes = treeRepository.listNodes(workspaceId, beforeSnapshot.id)
        val target = beforeNodes.firstOrNull { it.depth >= 1 && it.label != "AutoDoc" } ?: error("lock target missing")
        val beforeParentLabel = beforeNodes.firstOrNull { it.id == target.parentId }?.label ?: "AutoDoc"

        mockMvc.perform(
            post("/api/v1/tree/nodes/${target.id}/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
                .content(objectMapper.writeValueAsString(mapOf("locked" to true)))
        ).andExpect(status().isNoContent)

        treeService.rebuildWorkspace(workspaceId, ownerId, manual = true)

        val afterSnapshot = treeRepository.findActiveSnapshot(workspaceId) ?: error("active snapshot missing after rebuild")
        val afterNodes = treeRepository.listNodes(workspaceId, afterSnapshot.id)
        val lockedAfter = afterNodes.firstOrNull { it.label == target.label && it.locked }
        assertTrue(lockedAfter != null, "Locked node label must remain after rebuild")
        val afterParentLabel = afterNodes.firstOrNull { it.id == lockedAfter?.parentId }?.label ?: "AutoDoc"
        assertEquals(beforeParentLabel, afterParentLabel)
    }

    private fun findNodeContainingDoc(root: JsonNode, documentId: String): JsonNode? {
        val nodes = root.path("nodes")
        if (!nodes.isArray) {
            return null
        }
        return nodes.firstOrNull { node ->
            node.path("documents").any { it.asText() == documentId }
        }
    }

    private fun createDoc(title: String, body: String): com.autodoctree.api.db.DocumentRow {
        val created = documentRepository.create(
            workspaceId = workspaceId,
            title = title,
            bodyMarkdown = body,
            bodyText = body,
            sourceType = "EDITOR",
            createdBy = ownerId
        )
        pipelineStatusRepository.create(workspaceId, created.id)
        return created
    }

    private fun login(email: String, password: String): String {
        val response = mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "email" to email,
                            "password" to password
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        return objectMapper.readTree(response).path("access_token").asText()
    }
}
