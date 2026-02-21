package com.autodoctree.api.integration

import com.autodoctree.api.db.MembershipRepository
import com.autodoctree.api.db.UserRepository
import com.autodoctree.api.db.WorkspaceRepository
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchPaletteIntegrationTest {

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

    private lateinit var wsId: String
    private lateinit var token: String
    private lateinit var otherWsId: String
    private lateinit var otherToken: String

    @BeforeEach
    fun setup() {
        val user = userRepository.findByEmail("palette-owner@autodoc.local")
            ?: userRepository.create("palette-owner@autodoc.local", passwordEncoder.encode("password"))
        wsId = workspaceRepository.listByUser(user.id).firstOrNull()?.id
            ?: workspaceRepository.create("Palette WS", user.id).id.also { membershipRepository.create(it, user.id, "OWNER") }
        token = login("palette-owner@autodoc.local", "password")

        val otherUser = userRepository.findByEmail("palette-other@autodoc.local")
            ?: userRepository.create("palette-other@autodoc.local", passwordEncoder.encode("password"))
        otherWsId = workspaceRepository.listByUser(otherUser.id).firstOrNull()?.id
            ?: workspaceRepository.create("Palette Other", otherUser.id).id.also { membershipRepository.create(it, otherUser.id, "OWNER") }
        otherToken = login("palette-other@autodoc.local", "password")
    }

    @Test
    fun `history endpoint stores and lists entries per workspace context`() {
        mockMvc.perform(
            post("/api/v1/search/history")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", wsId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("eventType" to "COMMAND", "commandKey" to "tree")))
        ).andExpect(status().isCreated)

        mockMvc.perform(
            get("/api/v1/search/history")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", wsId)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].event_type").value("COMMAND"))
    }


    @Test
    fun `history endpoint denies cross-tenant workspace header`() {
        mockMvc.perform(
            get("/api/v1/search/history")
                .header("Authorization", "Bearer $otherToken")
                .header("X-Workspace-Id", wsId)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/search/history")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", otherWsId)
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
