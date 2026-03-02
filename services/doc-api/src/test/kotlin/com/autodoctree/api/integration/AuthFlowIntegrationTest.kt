package com.autodoctree.api.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var javaMailSender: JavaMailSender

    @BeforeEach
    fun resetMailSenderMock() {
        reset(javaMailSender)
    }

    @Test
    fun `register requires email verification and returns tokens`() {
        val email = "new-user-${UUID.randomUUID()}@autodoc.local"
        val verificationCode = requestSignupCode(email, "password123")

        mockMvc.perform(
            post("/api/v1/auth/register/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "email" to email,
                            "verification_code" to verificationCode
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.access_token").isNotEmpty)
            .andExpect(jsonPath("$.refresh_token").isNotEmpty)

        mockMvc.perform(
            post("/api/v1/auth/register/request-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "email" to email,
                            "password" to "password123"
                        )
                    )
                )
        ).andExpect(status().isConflict)
    }

    @Test
    fun `register rejects invalid verification code`() {
        val email = "new-user-${UUID.randomUUID()}@autodoc.local"
        requestSignupCode(email, "password123")

        mockMvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "email" to email,
                            "verification_code" to "000000"
                        )
                    )
                )
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.message").value("Verification code is invalid or expired"))
    }

    @Test
    fun `invite acceptance enforces invited email match`() {
        val ownerToken = login("owner@autodoc.local", "password")
        val workspaceId = firstWorkspaceId(ownerToken)

        val invitedEmail = "invitee-${UUID.randomUUID()}@autodoc.local"
        val outsiderEmail = "outsider-${UUID.randomUUID()}@autodoc.local"
        val invitedToken = registerAndLogin(invitedEmail)
        val outsiderToken = registerAndLogin(outsiderEmail)

        val inviteToken = createInvite(ownerToken, workspaceId, invitedEmail)

        mockMvc.perform(
            post("/api/v1/workspaces/invites/accept")
                .header("Authorization", "Bearer $outsiderToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("token" to inviteToken)))
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("TENANT_FORBIDDEN"))

        mockMvc.perform(
            post("/api/v1/workspaces/invites/accept")
                .header("Authorization", "Bearer $invitedToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("token" to inviteToken)))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.workspace_id").value(workspaceId))
    }

    @Test
    fun `login refresh logout flow works`() {
        val loginResponse = mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "email" to "owner@autodoc.local",
                            "password" to "password"
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.access_token").isNotEmpty)
            .andExpect(jsonPath("$.refresh_token").isNotEmpty)
            .andReturn()
            .response
            .contentAsString

        val refreshToken = objectMapper.readTree(loginResponse).get("refresh_token").asText()

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("refresh_token" to refreshToken)))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.access_token").isNotEmpty)
            .andExpect(jsonPath("$.refresh_token").isNotEmpty)

        mockMvc.perform(
            post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("refresh_token" to refreshToken)))
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `login preflight allows local frontend origins`() {
        mockMvc.perform(
            options("/api/v1/auth/login")
                .header("Origin", "http://localhost:5174")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
        ).andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"))

        mockMvc.perform(
            options("/api/v1/auth/register/request-code")
                .header("Origin", "http://localhost:5174")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
        ).andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"))

        mockMvc.perform(
            options("/api/v1/auth/register/verify")
                .header("Origin", "http://localhost:5174")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
        ).andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"))
    }

    private fun registerAndLogin(email: String): String {
        val code = requestSignupCode(email, "password123")
        val response = mockMvc.perform(
            post("/api/v1/auth/register/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "email" to email,
                            "verification_code" to code
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        return objectMapper.readTree(response).get("access_token").asText()
    }

    private fun requestSignupCode(email: String, password: String): String {
        reset(javaMailSender)
        mockMvc.perform(
            post("/api/v1/auth/register/request-code")
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
            .andExpect(jsonPath("$.expires_in_seconds").isNumber)

        val messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        verify(javaMailSender).send(messageCaptor.capture())
        val messageBody = messageCaptor.value.text.orEmpty()
        val match = Regex("""\b(\d{6})\b""").find(messageBody)
            ?: throw IllegalStateException("Verification code not found in email body")
        return match.groupValues[1]
    }

    private fun login(email: String, password: String): String {
        val payload = mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("email" to email, "password" to password)))
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        return objectMapper.readTree(payload).get("access_token").asText()
    }

    private fun firstWorkspaceId(accessToken: String): String {
        val payload = mockMvc.perform(
            get("/api/v1/workspaces")
                .header("Authorization", "Bearer $accessToken")
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val items = objectMapper.readTree(payload).path("items")
        if (!items.isArray || items.isEmpty) {
            throw IllegalStateException("No workspace returned")
        }
        return items.first().path("id").asText()
    }

    private fun createInvite(accessToken: String, workspaceId: String, email: String): String {
        val payload = mockMvc.perform(
            post("/api/v1/workspaces/$workspaceId/invites")
                .header("Authorization", "Bearer $accessToken")
                .header("X-Workspace-Id", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("email" to email, "role" to "MEMBER")))
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        return objectMapper.readTree(payload).path("invite_token").asText()
    }
}
