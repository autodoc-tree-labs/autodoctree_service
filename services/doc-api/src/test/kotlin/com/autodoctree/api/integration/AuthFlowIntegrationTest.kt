package com.autodoctree.api.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

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
    }
}
