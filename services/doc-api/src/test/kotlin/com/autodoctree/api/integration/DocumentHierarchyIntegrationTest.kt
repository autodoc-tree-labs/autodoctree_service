package com.autodoctree.api.integration

import com.autodoctree.api.db.MembershipRepository
import com.autodoctree.api.db.TreeRepository
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
class DocumentHierarchyIntegrationTest {

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
    private lateinit var treeRepository: TreeRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var wsAId: String
    private lateinit var wsBId: String
    private lateinit var tokenA: String
    private lateinit var tokenB: String

    @BeforeEach
    fun setup() {
        val userA = userRepository.findByEmail("hierarchy-a@autodoc.local")
            ?: userRepository.create("hierarchy-a@autodoc.local", passwordEncoder.encode("password"))
        val userB = userRepository.findByEmail("hierarchy-b@autodoc.local")
            ?: userRepository.create("hierarchy-b@autodoc.local", passwordEncoder.encode("password"))

        wsAId = workspaceRepository.listByUser(userA.id).firstOrNull()?.id
            ?: workspaceRepository.create("Hierarchy-A", userA.id).id.also {
                membershipRepository.create(it, userA.id, "OWNER")
            }

        wsBId = workspaceRepository.listByUser(userB.id).firstOrNull()?.id
            ?: workspaceRepository.create("Hierarchy-B", userB.id).id.also {
                membershipRepository.create(it, userB.id, "OWNER")
            }

        tokenA = login("hierarchy-a@autodoc.local", "password")
        tokenB = login("hierarchy-b@autodoc.local", "password")
    }

    @Test
    fun `create child persists parent relationship and parent delete cascades to descendants`() {
        val rootId = createDocument(tokenA, wsAId, "Hierarchy Root", null)
        val childId = createDocument(tokenA, wsAId, "Hierarchy Child", rootId)
        val grandChildId = createDocument(tokenA, wsAId, "Hierarchy GrandChild", childId)

        val childResponse = mockMvc.perform(
            get("/api/v1/documents/$childId")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val childPayload = objectMapper.readTree(childResponse)
        assertEquals(rootId, childPayload.path("parent_document_id").asText())

        val listResponse = mockMvc.perform(
            get("/api/v1/documents")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val listPayload = objectMapper.readTree(listResponse).path("items")
        val childNode = listPayload.firstOrNull { it.path("id").asText() == childId }
        assertTrue(childNode != null, "Expected child document in list response")
        assertEquals(rootId, childNode?.path("parent_document_id")?.asText())

        mockMvc.perform(
            post("/api/v1/documents/$childId/favorite")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/v1/documents/$grandChildId/favorite")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isNoContent)

        val activeSnapshot = treeRepository.createSnapshot(
            workspaceId = wsAId,
            status = "ACTIVE",
            movedRatio = 0.0,
            churnCount = 0,
            nodeRenameCount = 0
        )
        val rootNode = treeRepository.insertNode(
            workspaceId = wsAId,
            snapshotId = activeSnapshot.id,
            parentId = null,
            label = "AutoDoc",
            depth = 0,
            locked = false
        )
        treeRepository.insertMembership(
            workspaceId = wsAId,
            snapshotId = activeSnapshot.id,
            nodeId = rootNode.id,
            documentId = rootId,
            rationaleJson = "{}"
        )
        treeRepository.insertMembership(
            workspaceId = wsAId,
            snapshotId = activeSnapshot.id,
            nodeId = rootNode.id,
            documentId = childId,
            rationaleJson = "{}"
        )
        treeRepository.insertMembership(
            workspaceId = wsAId,
            snapshotId = activeSnapshot.id,
            nodeId = rootNode.id,
            documentId = grandChildId,
            rationaleJson = "{}"
        )

        val activeTreeBeforeDelete = mockMvc.perform(
            get("/api/v1/tree/active?view=topic")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val treeBeforeNodes = objectMapper.readTree(activeTreeBeforeDelete).path("nodes")
        val treeBeforeDocIds = mutableSetOf<String>()
        treeBeforeNodes.forEach { node ->
            node.path("documents").forEach { docNode ->
                treeBeforeDocIds += docNode.asText()
            }
        }
        assertTrue(treeBeforeDocIds.contains(rootId), "Expected active tree to include root before delete")

        mockMvc.perform(
            delete("/api/v1/documents/$rootId")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/v1/documents/$childId")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            get("/api/v1/documents/$grandChildId")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isNotFound)

        val trashResponse = mockMvc.perform(
            get("/api/v1/documents/trash")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val trashItems = objectMapper.readTree(trashResponse).path("items")
        assertTrue(trashItems.any { it.path("id").asText() == rootId }, "Expected root document in trash")
        assertTrue(trashItems.any { it.path("id").asText() == childId }, "Expected child document in trash")
        assertTrue(trashItems.any { it.path("id").asText() == grandChildId }, "Expected grandchild document in trash")

        val favoritesResponse = mockMvc.perform(
            get("/api/v1/documents/favorites")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val favoriteItems = objectMapper.readTree(favoritesResponse).path("items")
        assertTrue(favoriteItems.none { it.path("document_id").asText() == childId }, "Expected child favorite removed")
        assertTrue(favoriteItems.none { it.path("document_id").asText() == grandChildId }, "Expected grandchild favorite removed")

        val activeTreeAfterDelete = mockMvc.perform(
            get("/api/v1/tree/active?view=topic")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val deletedIds = setOf(rootId, childId, grandChildId)
        val treeAfterNodes = objectMapper.readTree(activeTreeAfterDelete).path("nodes")
        treeAfterNodes.forEach { node ->
            node.path("documents").forEach { docNode ->
                assertTrue(docNode.asText() !in deletedIds, "Deleted documents must not appear in active tree documents")
            }
            node.path("document_summaries").forEach { summary ->
                assertTrue(summary.path("id").asText() !in deletedIds, "Deleted documents must not appear in active tree summaries")
            }
        }
    }

    @Test
    fun `cross-workspace parent reference is rejected`() {
        val wsAParentId = createDocument(tokenA, wsAId, "WS-A Parent", null)

        mockMvc.perform(
            post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "title" to "WS-B Child",
                            "body_markdown" to "",
                            "source_type" to "EDITOR",
                            "parent_document_id" to wsAParentId
                        )
                    )
                )
        ).andExpect(status().isBadRequest)
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
