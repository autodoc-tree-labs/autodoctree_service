package com.autodoctree.api.integration

import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.FeedbackRepository
import com.autodoctree.api.db.ActiveLearningQuestionRepository
import com.autodoctree.api.db.PipelineStatusRepository
import com.autodoctree.api.db.TreeRepository
import com.autodoctree.api.db.UserRepository
import com.autodoctree.api.db.WorkspaceRepository
import com.autodoctree.api.domain.TreeService
import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
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
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "feature.user-rules-v1=true",
        "feature.feedback-routing-v2=true",
        "feature.admin-tree-debug=true",
        "tree.multiview-enabled=true",
        "tree.template-isolation-enabled=true"
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

    @Autowired
    private lateinit var activeLearningQuestionRepository: ActiveLearningQuestionRepository

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
        val response = mockMvc.perform(
            get("/api/v1/admin/tree/debug/neighbors")
                .param("document_id", debugDocId)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.document_id").value(debugDocId))
            .andExpect(jsonPath("$.neighbors").isArray)
            .andReturn()
            .response
            .contentAsString

        val root = objectMapper.readTree(response)
        val firstNeighbor = root.path("neighbors").firstOrNull()
        if (firstNeighbor != null) {
            assertTrue(firstNeighbor.has("neighbor_doc_id"))
            assertTrue(firstNeighbor.has("title"))
            assertTrue(firstNeighbor.has("lex_sim"))
            assertTrue(firstNeighbor.has("entity_overlap"))
            assertTrue(firstNeighbor.has("final_sim"))
            assertTrue(firstNeighbor.path("gate_flags").has("lexical_gate_passed"))
        }
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
        assertTrue(node?.has("node_type") == true)
        assertTrue(summary!!.has("quarantine_reason"))
        assertTrue(summary.has("placement_confidence"))
        assertTrue(summary.path("placement_candidates").isArray)
    }

    @Test
    fun `template-like document is quarantined with template signals`() {
        val templateBody = List(60) { "approval request form footer section" }.joinToString(" ")
        val templateDoc = createDoc("반복 양식 문서", templateBody)
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
        val summary = findDocumentSummary(root, templateDoc.id)
        assertTrue(summary != null, "Expected template document summary for doc=${templateDoc.id}")
        assertEquals("TEMPLATE", summary?.path("quarantine_reason")?.asText())
        assertTrue(summary!!.path("template_score").asDouble(0.0) > 0.0)
        assertTrue(summary.path("template_ngram_repeat_ratio").asDouble(0.0) > 0.0)
        assertTrue(summary.path("template_reasons").isArray)
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
    fun `member can retry failed stage from document endpoint`() {
        pipelineStatusRepository.updateStage(
            workspaceId = workspaceId,
            documentId = debugDocId,
            stage = Stage.EMBED,
            status = StageStatus.FAILED,
            failureReason = "embedding failed"
        )

        mockMvc.perform(
            post("/api/v1/documents/$debugDocId/pipeline/retry")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $memberToken")
                .header("X-Workspace-Id", workspaceId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("stage" to "EMBED")
                    )
                )
        ).andExpect(status().isNoContent)
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
    fun `rule preview returns routing result for sample doc`() {
        val active = treeRepository.findActiveSnapshot(workspaceId) ?: error("active snapshot missing")
        val targetNode = treeRepository.listNodes(workspaceId, active.id).firstOrNull { it.depth >= 1 && it.label != "AutoDoc" }
            ?: error("target node missing")

        mockMvc.perform(
            post("/api/v1/admin/tree/rules/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "document_id" to debugDocId,
                            "rule_type" to "SOURCE_TYPE",
                            "rule_value" to "editor",
                            "rule_effect" to "SOFT",
                            "node_id" to targetNode.id
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.document_id").value(debugDocId))
            .andExpect(jsonPath("$.rule_type").value("SOURCE_TYPE"))
            .andExpect(jsonPath("$.rule_effect").value("SOFT"))
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.target_node_id").value(targetNode.id))
    }

    @Test
    fun `admin can update user rule effect and type`() {
        val active = treeRepository.findActiveSnapshot(workspaceId) ?: error("active snapshot missing")
        val targetNode = treeRepository.listNodes(workspaceId, active.id).firstOrNull { it.depth >= 1 && it.label != "AutoDoc" }
            ?: error("target node missing")

        val createResponse = mockMvc.perform(
            post("/api/v1/admin/tree/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "rule_type" to "TAG",
                            "rule_value" to "quality",
                            "rule_effect" to "SOFT",
                            "node_id" to targetNode.id
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.rule_effect").value("SOFT"))
            .andReturn()
            .response
            .contentAsString
        val createdRuleId = objectMapper.readTree(createResponse).path("id").asText()

        mockMvc.perform(
            patch("/api/v1/admin/tree/rules/$createdRuleId")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "rule_type" to "SOURCE_TYPE",
                            "rule_value" to "editor",
                            "rule_effect" to "HARD",
                            "node_id" to targetNode.id
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.rule_type").value("SOURCE_TYPE"))
            .andExpect(jsonPath("$.rule_effect").value("HARD"))

        val listResponse = mockMvc.perform(
            get("/api/v1/admin/tree/rules")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val items = objectMapper.readTree(listResponse).path("items")
        val updated = items.firstOrNull { item -> item.path("id").asText() == createdRuleId }
        assertTrue(updated != null, "Expected updated rule in list response")
        assertEquals("SOURCE_TYPE", updated?.path("rule_type")?.asText())
        assertEquals("HARD", updated?.path("rule_effect")?.asText())
    }

    @Test
    fun `admin audit endpoint supports filter sort and structured payload`() {
        mockMvc.perform(
            post("/api/v1/admin/jobs/retry")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "document_id" to debugDocId,
                            "stage" to "EMBED"
                        )
                    )
                )
        ).andExpect(status().isNoContent)

        val response = mockMvc.perform(
            get("/api/v1/admin/audit")
                .param("type", "admin.retry")
                .param("actor_user_id", ownerId)
                .param("q", debugDocId)
                .param("sort", "asc")
                .param("limit", "20")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.sort").value("asc"))
            .andExpect(jsonPath("$.items[0].action").value("admin.retry"))
            .andExpect(jsonPath("$.items[0].payload.document_id").value(debugDocId))
            .andReturn()
            .response
            .contentAsString

        val items = objectMapper.readTree(response).path("items")
        val matched = items.any { item ->
            item.path("action").asText() == "admin.retry" &&
                item.path("payload").path("document_id").asText() == debugDocId
        }
        assertTrue(matched, "Expected filtered audit list to include admin.retry payload")
    }

    @Test
    fun `question inbox can answer cluster choice question`() {
        val active = treeRepository.findActiveSnapshot(workspaceId) ?: error("active snapshot missing")
        val membership = treeRepository.findMembershipByDocInSnapshot(workspaceId, active.id, debugDocId)
            ?: error("debug document membership missing")
        val node = treeRepository.findNodeByWorkspace(workspaceId, membership.nodeId) ?: error("node missing")
        val created = activeLearningQuestionRepository.create(
            workspaceId = workspaceId,
            snapshotId = active.id,
            questionType = "DOC_CLUSTER_CHOICE",
            documentId = debugDocId,
            payloadJson = objectMapper.writeValueAsString(
                mapOf(
                    "document_id" to debugDocId,
                    "document_title" to "질문 테스트 문서",
                    "option_a" to mapOf("node_id" to membership.nodeId, "label" to node.label, "score" to 0.8),
                    "option_b" to mapOf("node_id" to membership.nodeId, "label" to node.label, "score" to 0.6)
                )
            ),
            impactScore = 0.74,
            expiresAt = LocalDateTime.now().plusHours(2)
        )

        val listResponse = mockMvc.perform(
            get("/api/v1/questions")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val listed = objectMapper.readTree(listResponse).path("items")
        val listedQuestion = listed.firstOrNull { item -> item.path("id").asText() == created.id }
        assertTrue(listedQuestion != null, "Expected created question in /questions response")
        assertEquals("DOC_CLUSTER_CHOICE", listedQuestion?.path("question_type")?.asText())

        mockMvc.perform(
            post("/api/v1/questions/${created.id}/answer")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
                .content(objectMapper.writeValueAsString(mapOf("answer" to "A")))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ANSWERED"))

        val updated = activeLearningQuestionRepository.findByWorkspaceAndId(workspaceId, created.id)
        assertEquals("ANSWERED", updated?.status)
        assertEquals("A", updated?.answerValue)
    }

    @Test
    fun `admin question analytics and controls endpoints work`() {
        val active = treeRepository.findActiveSnapshot(workspaceId) ?: error("active snapshot missing")
        activeLearningQuestionRepository.create(
            workspaceId = workspaceId,
            snapshotId = active.id,
            questionType = "DOC_PAIR_RELATION",
            documentId = debugDocId,
            payloadJson = objectMapper.writeValueAsString(
                mapOf(
                    "doc_a_id" to debugDocId,
                    "doc_a_title" to "A",
                    "doc_b_id" to "doc-x",
                    "doc_b_title" to "B"
                )
            ),
            impactScore = 0.55,
            expiresAt = LocalDateTime.now().plusHours(2)
        )

        mockMvc.perform(
            get("/api/v1/admin/tree/questions/analytics")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.open_count").isNumber)
            .andExpect(jsonPath("$.answer_rate").isNumber)
            .andExpect(jsonPath("$.control.enabled").exists())

        mockMvc.perform(
            post("/api/v1/admin/tree/questions/generate")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.generated_count").isNumber)

        mockMvc.perform(
            patch("/api/v1/admin/tree/questions/control")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
                .content(objectMapper.writeValueAsString(mapOf("enabled" to false)))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))

        mockMvc.perform(
            post("/api/v1/admin/tree/questions/expire")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.expired_count").isNumber)
    }

    @Test
    fun `tree multi-view endpoint creates and returns view partition snapshots`() {
        mockMvc.perform(
            get("/api/v1/trees")
                .param("view", "project")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.view_type").value("project"))
            .andExpect(jsonPath("$.snapshot_id").exists())
            .andExpect(jsonPath("$.nodes").isArray)

        mockMvc.perform(
            get("/api/v1/trees")
                .param("view", "version")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.view_type").value("version"))
            .andExpect(jsonPath("$.snapshot_id").exists())
            .andExpect(jsonPath("$.nodes").isArray)

        mockMvc.perform(
            get("/api/v1/tree/snapshots")
                .param("view", "version")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].view_type").value("version"))
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

    private fun findDocumentSummary(root: JsonNode, documentId: String): JsonNode? {
        val nodes = root.path("nodes")
        if (!nodes.isArray) {
            return null
        }
        nodes.forEach { node ->
            val summary = node.path("document_summaries").firstOrNull { item ->
                item.path("id").asText() == documentId
            }
            if (summary != null) {
                return summary
            }
        }
        return null
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
