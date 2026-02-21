package com.autodoctree.api.integration

import com.autodoctree.api.db.DocumentRepository
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
class DocumentFavoriteIntegrationTest {

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
    private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var wsAId: String
    private lateinit var wsBId: String
    private lateinit var wsADocId: String
    private lateinit var tokenA: String
    private lateinit var tokenB: String

    @BeforeEach
    fun setup() {
        val userA = userRepository.findByEmail("favorite-a@autodoc.local")
            ?: userRepository.create("favorite-a@autodoc.local", passwordEncoder.encode("password"))
        val userB = userRepository.findByEmail("favorite-b@autodoc.local")
            ?: userRepository.create("favorite-b@autodoc.local", passwordEncoder.encode("password"))

        wsAId = workspaceRepository.listByUser(userA.id).firstOrNull()?.id
            ?: workspaceRepository.create("Favorite-A", userA.id).id.also {
                membershipRepository.create(it, userA.id, "OWNER")
            }

        wsBId = workspaceRepository.listByUser(userB.id).firstOrNull()?.id
            ?: workspaceRepository.create("Favorite-B", userB.id).id.also {
                membershipRepository.create(it, userB.id, "OWNER")
            }

        wsADocId = documentRepository.listByWorkspace(wsAId, null, null, 0, 1).firstOrNull()?.id
            ?: documentRepository.create(
                workspaceId = wsAId,
                title = "즐겨찾기 테스트 문서",
                bodyMarkdown = "",
                bodyText = "",
                sourceType = "EDITOR",
                createdBy = userA.id
            ).id

        tokenA = login("favorite-a@autodoc.local", "password")
        tokenB = login("favorite-b@autodoc.local", "password")
    }

    @Test
    fun `favorite add list remove is idempotent`() {
        mockMvc.perform(
            post("/api/v1/documents/$wsADocId/favorite")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/v1/documents/$wsADocId/favorite")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isNoContent)

        val listed = mockMvc.perform(
            get("/api/v1/documents/favorites")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val items = objectMapper.readTree(listed).path("items")
        val matched = items.filter { it.path("document_id").asText() == wsADocId }
        assertEquals(1, matched.size)
        assertTrue(matched.first().path("created_at").asText().isNotBlank())

        mockMvc.perform(
            delete("/api/v1/documents/$wsADocId/favorite")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isNoContent)

        val removed = mockMvc.perform(
            get("/api/v1/documents/favorites")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val removedItems = objectMapper.readTree(removed).path("items")
        assertTrue(removedItems.none { it.path("document_id").asText() == wsADocId })
    }

    @Test
    fun `cross-tenant favorite add is rejected`() {
        mockMvc.perform(
            post("/api/v1/documents/$wsADocId/favorite")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/api/v1/documents/$wsADocId/favorite")
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isForbidden)
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
