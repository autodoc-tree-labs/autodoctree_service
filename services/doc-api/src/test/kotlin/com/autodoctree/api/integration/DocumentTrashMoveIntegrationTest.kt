package com.autodoctree.api.integration

import com.autodoctree.api.db.MembershipRepository
import com.autodoctree.api.db.UserRepository
import com.autodoctree.api.db.WorkspaceRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentTrashMoveIntegrationTest {

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
    private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var wsAId: String
    private lateinit var wsBId: String
    private lateinit var tokenA: String
    private lateinit var tokenB: String

    @BeforeEach
    fun setup() {
        val userA = userRepository.findByEmail("trash-move-a@autodoc.local")
            ?: userRepository.create("trash-move-a@autodoc.local", passwordEncoder.encode("password"))
        val userB = userRepository.findByEmail("trash-move-b@autodoc.local")
            ?: userRepository.create("trash-move-b@autodoc.local", passwordEncoder.encode("password"))

        wsAId = workspaceRepository.listByUser(userA.id).firstOrNull()?.id
            ?: workspaceRepository.create("TrashMove-A", userA.id).id.also {
                membershipRepository.create(it, userA.id, "OWNER")
            }

        wsBId = workspaceRepository.listByUser(userB.id).firstOrNull()?.id
            ?: workspaceRepository.create("TrashMove-B", userB.id).id.also {
                membershipRepository.create(it, userB.id, "OWNER")
            }

        tokenA = login("trash-move-a@autodoc.local", "password")
        tokenB = login("trash-move-b@autodoc.local", "password")
    }

    @Test
    fun `move document to another parent and root`() {
        val rootA = createDocument(tokenA, wsAId, "Move Root A", null)
        val rootB = createDocument(tokenA, wsAId, "Move Root B", null)
        val child = createDocument(tokenA, wsAId, "Move Child", rootA)

        mockMvc.perform(
            post("/api/v1/documents/$child/move")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(objectMapper.writeValueAsString(mapOf("parent_document_id" to rootB)))
        ).andExpect(status().isNoContent)

        assertEquals(rootB, getParentDocumentId(tokenA, wsAId, child))

        mockMvc.perform(
            post("/api/v1/documents/$child/move")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(objectMapper.writeValueAsString(mapOf("parent_document_id" to null)))
        ).andExpect(status().isNoContent)

        assertTrue(getParentDocumentId(tokenA, wsAId, child) == null)
    }

    @Test
    fun `move rejects parent cycle`() {
        val root = createDocument(tokenA, wsAId, "Cycle Root", null)
        val child = createDocument(tokenA, wsAId, "Cycle Child", root)
        val grandChild = createDocument(tokenA, wsAId, "Cycle GrandChild", child)

        mockMvc.perform(
            post("/api/v1/documents/$root/move")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(objectMapper.writeValueAsString(mapOf("parent_document_id" to grandChild)))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `trash list and restore document`() {
        val documentId = createDocument(tokenA, wsAId, "Trash Restore Target", null)

        mockMvc.perform(
            delete("/api/v1/documents/$documentId")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isNoContent)

        val trashAfterDelete = mockMvc.perform(
            get("/api/v1/documents/trash")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val trashItems = objectMapper.readTree(trashAfterDelete).path("items")
        assertTrue(trashItems.any { it.path("id").asText() == documentId })

        mockMvc.perform(
            post("/api/v1/documents/$documentId/restore")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isNoContent)

        val trashAfterRestore = mockMvc.perform(
            get("/api/v1/documents/trash")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val restoredTrashItems = objectMapper.readTree(trashAfterRestore).path("items")
        assertTrue(restoredTrashItems.none { it.path("id").asText() == documentId })

        mockMvc.perform(
            get("/api/v1/documents/$documentId")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
    }

    @Test
    fun `cross-tenant move and restore are denied`() {
        val documentId = createDocument(tokenA, wsAId, "Cross Tenant Target", null)

        mockMvc.perform(
            post("/api/v1/documents/$documentId/move")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
                .content(objectMapper.writeValueAsString(mapOf("parent_document_id" to null)))
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/api/v1/documents/$documentId/move")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
                .content(objectMapper.writeValueAsString(mapOf("parent_document_id" to null)))
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/v1/documents/$documentId/restore")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/api/v1/documents/$documentId/restore")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)
    }

    private fun createDocument(token: String, workspaceId: String, title: String, parentDocumentId: String?): String {
        val payload = mutableMapOf<String, Any?>(
            "title" to title,
            "body_markdown" to "",
            "source_type" to "EDITOR"
        )
        if (parentDocumentId != null) {
            payload["parent_document_id"] = parentDocumentId
        }

        val response = mockMvc.perform(
            post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
                .content(objectMapper.writeValueAsString(payload))
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        return objectMapper.readTree(response).path("id").asText()
    }

    private fun getParentDocumentId(token: String, workspaceId: String, documentId: String): String? {
        val response = mockMvc.perform(
            get("/api/v1/documents/$documentId")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val parentNode = objectMapper.readTree(response).path("parent_document_id")
        return if (parentNode.isNull || parentNode.asText().isBlank()) null else parentNode.asText()
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
        return objectMapper.readTree(response).path("access_token").asText()
    }
}
