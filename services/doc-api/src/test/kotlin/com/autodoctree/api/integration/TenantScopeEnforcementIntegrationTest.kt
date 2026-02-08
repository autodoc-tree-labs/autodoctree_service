package com.autodoctree.api.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantScopeEnforcementIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var token: String

    @BeforeEach
    fun setup() {
        val response = mockMvc.perform(
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
        ).andReturn().response.contentAsString

        token = objectMapper.readTree(response).get("access_token").asText()
    }

    @Test
    fun `tenant scoped endpoint fails without workspace header`() {
        mockMvc.perform(
            get("/api/v1/documents")
                .header("Authorization", "Bearer $token")
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            get("/api/v1/search")
                .param("q", "hello")
                .header("Authorization", "Bearer $token")
        ).andExpect(status().isBadRequest)
    }
}
