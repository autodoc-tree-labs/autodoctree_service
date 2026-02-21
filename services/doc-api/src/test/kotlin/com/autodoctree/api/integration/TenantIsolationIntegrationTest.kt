package com.autodoctree.api.integration

import com.autodoctree.api.db.AttachmentRepository
import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.MembershipRepository
import com.autodoctree.api.db.OutboxRepository
import com.autodoctree.api.db.PipelineStatusRepository
import com.autodoctree.api.db.UserRepository
import com.autodoctree.api.db.WorkspaceRepository
import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantIsolationIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var workspaceRepository: WorkspaceRepository

    @Autowired
    private lateinit var membershipRepository: MembershipRepository

    @Autowired
    private lateinit var documentRepository: DocumentRepository

    @Autowired
    private lateinit var pipelineStatusRepository: PipelineStatusRepository

    @Autowired
    private lateinit var outboxRepository: OutboxRepository

    @Autowired
    private lateinit var attachmentRepository: AttachmentRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var wsAId: String
    private lateinit var wsBId: String
    private lateinit var wsAOwnerUserId: String
    private lateinit var wsADocId: String
    private lateinit var wsAAttachmentId: String
    private lateinit var tokenA: String
    private lateinit var tokenB: String

    @BeforeEach
    fun setup() {
        val userA = userRepository.findByEmail("wsa-owner@autodoc.local")
            ?: userRepository.create("wsa-owner@autodoc.local", passwordEncoder.encode("password"))
        val userB = userRepository.findByEmail("wsb-owner@autodoc.local")
            ?: userRepository.create("wsb-owner@autodoc.local", passwordEncoder.encode("password"))
        wsAOwnerUserId = userA.id

        wsAId = workspaceRepository.listByUser(userA.id).firstOrNull()?.id
            ?: workspaceRepository.create("Workspace-A", userA.id).id.also {
                membershipRepository.create(it, userA.id, "OWNER")
            }

        wsBId = workspaceRepository.listByUser(userB.id).firstOrNull()?.id
            ?: workspaceRepository.create("Workspace-B", userB.id).id.also {
                membershipRepository.create(it, userB.id, "OWNER")
            }

        val existingDoc = documentRepository.listByWorkspace(wsAId, null, null, 0, 1).firstOrNull()
        if (existingDoc == null) {
            val doc = documentRepository.create(
                workspaceId = wsAId,
                title = "Tenant A Secret",
                bodyMarkdown = "secret",
                bodyText = "secret",
                sourceType = "EDITOR",
                createdBy = userA.id
            )
            pipelineStatusRepository.create(wsAId, doc.id)
            wsADocId = doc.id
            val attachment = attachmentRepository.create(
                workspaceId = wsAId,
                documentId = doc.id,
                filename = "secret.txt",
                contentType = "text/plain",
                size = 12,
                objectKey = "workspaces/$wsAId/attachments/$doc.id/secret.txt",
                checksumSha256 = null
            )
            wsAAttachmentId = attachment.id
        } else {
            wsADocId = existingDoc.id
            wsAAttachmentId = attachmentRepository.listByWorkspaceAndDocument(wsAId, wsADocId).firstOrNull()?.id
                ?: attachmentRepository.create(
                    workspaceId = wsAId,
                    documentId = wsADocId,
                    filename = "secret.txt",
                    contentType = "text/plain",
                    size = 12,
                    objectKey = "workspaces/$wsAId/attachments/$wsADocId/secret.txt",
                    checksumSha256 = null
                ).id
        }

        tokenA = login("wsa-owner@autodoc.local", "password")
        tokenB = login("wsb-owner@autodoc.local", "password")
    }

    @Test
    fun `wsB cannot access wsA tenant resources`() {
        mockMvc.perform(
            get("/api/v1/documents/$wsADocId")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            get("/api/v1/documents")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.id == '$wsADocId')]").isEmpty)

        mockMvc.perform(
            get("/api/v1/documents/favorites")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.document_id == '$wsADocId')]").isEmpty)

        mockMvc.perform(
            post("/api/v1/documents/$wsADocId/favorite")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            get("/api/v1/documents/trash")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.id == '$wsADocId')]").isEmpty)

        mockMvc.perform(
            post("/api/v1/documents/$wsADocId/restore")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/api/v1/documents/$wsADocId/move")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("parent_document_id" to null)
                    )
                )
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            get("/api/v1/search")
                .param("q", "Tenant A Secret")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.document_id == '$wsADocId')]").isEmpty)

        mockMvc.perform(
            get("/api/v1/tree/active")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/api/v1/trees")
                .param("view", "project")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/tree/rebuild/status")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/documents/$wsADocId/explain")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.node_id").isEmpty)

        mockMvc.perform(
            post("/api/v1/documents/$wsADocId/explain/accept")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/api/v1/documents/$wsADocId/pipeline/retry")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("stage" to "EMBED")
                    )
                )
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/v1/attachments/presign")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "document_id" to wsADocId,
                            "filename" to "x.txt",
                            "content_type" to "text/plain",
                            "size" to 1
                        )
                    )
                )
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/api/v1/attachments/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
                .content(objectMapper.writeValueAsString(mapOf("attachment_id" to wsAAttachmentId)))
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            get("/api/v1/admin/jobs")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/admin/audit")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/admin/tree/policy")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            patch("/api/v1/admin/tree/policy")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "auto_threshold" to 0.9,
                            "recommend_threshold" to 0.7,
                            "quarantine_enabled" to true,
                            "reranker_enabled" to false
                        )
                    )
                )
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/admin/tree/debug/neighbors")
                .param("document_id", wsADocId)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/admin/tree/debug/docs/$wsADocId")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/admin/tree/rules")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/v1/admin/tree/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "rule_type" to "TITLE_CONTAINS",
                            "rule_value" to "tenant",
                            "node_id" to "node-x"
                        )
                    )
                )
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            patch("/api/v1/admin/tree/rules/rule-a")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "rule_type" to "SOURCE_TYPE",
                            "rule_value" to "upload",
                            "rule_effect" to "SOFT",
                            "node_id" to "node-x"
                        )
                    )
                )
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/v1/admin/tree/rules/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "document_id" to wsADocId,
                            "rule_type" to "TITLE_CONTAINS",
                            "rule_value" to "tenant",
                            "rule_effect" to "HARD",
                            "node_id" to "node-x"
                        )
                    )
                )
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/questions")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/v1/questions/question-a/answer")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
                .content(objectMapper.writeValueAsString(mapOf("answer" to "A")))
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/admin/tree/questions/analytics")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            patch("/api/v1/admin/tree/questions/control")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
                .content(objectMapper.writeValueAsString(mapOf("enabled" to false)))
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/v1/admin/tree/questions/expire")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/v1/admin/tree/questions/generate")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            patch("/api/v1/workspaces/$wsAId/members/$wsAOwnerUserId")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
                .content(objectMapper.writeValueAsString(mapOf("role" to "VIEWER")))
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            delete("/api/v1/workspaces/$wsAId/members/$wsAOwnerUserId")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/v1/workspaces/$wsAId/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "email" to "blocked-invite@autodoc.local",
                            "role" to "MEMBER"
                        )
                    )
                )
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `owner can create workspace invite with matching tenant scope`() {
        mockMvc.perform(
            post("/api/v1/workspaces/$wsAId/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "email" to "invitee@autodoc.local",
                            "role" to "VIEWER"
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.invite_token").isString)
    }

    @Test
    fun `attachment completion rejects object key outside workspace namespace`() {
        val leakedAttachment = attachmentRepository.create(
            workspaceId = wsAId,
            documentId = wsADocId,
            filename = "leak.txt",
            contentType = "text/plain",
            size = 1,
            objectKey = "workspaces/$wsBId/attachments/$wsADocId/leak.txt",
            checksumSha256 = null
        )

        mockMvc.perform(
            post("/api/v1/attachments/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("attachment_id" to leakedAttachment.id)
                    )
                )
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `owner can request failed stage retry from document endpoint`() {
        pipelineStatusRepository.updateStage(
            workspaceId = wsAId,
            documentId = wsADocId,
            stage = Stage.EMBED,
            status = StageStatus.FAILED,
            failureReason = "embedding failed"
        )

        mockMvc.perform(
            post("/api/v1/documents/$wsADocId/pipeline/retry")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("stage" to "EMBED")
                    )
                )
        ).andExpect(status().isNoContent)

        val hasRetryEvent = outboxRepository.listByWorkspace(wsAId, wsADocId).any { event ->
            if (event.eventType != "StageRetry") {
                return@any false
            }
            val payload = objectMapper.readTree(event.payloadJson)
            payload.path("stage").asText() == "EMBED"
        }
        assertTrue(hasRetryEvent, "Expected StageRetry outbox event for failed EMBED stage")
    }

    private fun login(email: String, password: String): String {
        val response = mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("email" to email, "password" to password)))
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val payload = objectMapper.readTree(response)
        return payload.get("access_token").asText()
    }
}
