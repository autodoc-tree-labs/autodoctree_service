package com.autodoctree.api.integration

import com.autodoctree.api.db.MembershipRepository
import com.autodoctree.api.db.AttachmentRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentBlocksIntegrationTest {

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

    @Autowired
    private lateinit var attachmentRepository: AttachmentRepository

    private lateinit var wsAId: String
    private lateinit var wsBId: String
    private lateinit var tokenA: String
    private lateinit var tokenB: String
    private lateinit var userAId: String

    @BeforeEach
    fun setup() {
        val userA = userRepository.findByEmail("block-editor-a@autodoc.local")
            ?: userRepository.create("block-editor-a@autodoc.local", passwordEncoder.encode("password"))
        val userB = userRepository.findByEmail("block-editor-b@autodoc.local")
            ?: userRepository.create("block-editor-b@autodoc.local", passwordEncoder.encode("password"))
        userAId = userA.id

        wsAId = workspaceRepository.listByUser(userA.id).firstOrNull()?.id
            ?: workspaceRepository.create("BlockEditor-A", userA.id).id.also {
                membershipRepository.create(it, userA.id, "OWNER")
            }

        wsBId = workspaceRepository.listByUser(userB.id).firstOrNull()?.id
            ?: workspaceRepository.create("BlockEditor-B", userB.id).id.also {
                membershipRepository.create(it, userB.id, "OWNER")
            }

        tokenA = login("block-editor-a@autodoc.local", "password")
        tokenB = login("block-editor-b@autodoc.local", "password")
    }

    @Test
    fun `blocks_json create and patch sync markdown and metadata`() {
        val createResponse = mockMvc.perform(
            post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "title" to "Block Editor Sample",
                            "body_markdown" to "",
                            "blocks_json" to mapOf(
                                "type" to "doc",
                                "content" to listOf(
                                    mapOf(
                                        "type" to "heading",
                                        "attrs" to mapOf("level" to 1),
                                        "content" to listOf(mapOf("type" to "text", "text" to "블록 문서 제목"))
                                    ),
                                    mapOf(
                                        "type" to "paragraph",
                                        "content" to listOf(mapOf("type" to "text", "text" to "block-sync-keyword-initial"))
                                    ),
                                    mapOf(
                                        "type" to "taskList",
                                        "content" to listOf(
                                            mapOf(
                                                "type" to "taskItem",
                                                "attrs" to mapOf("checked" to true),
                                                "content" to listOf(
                                                    mapOf(
                                                        "type" to "paragraph",
                                                        "content" to listOf(mapOf("type" to "text", "text" to "할 일"))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            ),
                            "source_type" to "EDITOR"
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val documentId = objectMapper.readTree(createResponse).path("id").asText()
        val created = getDocument(tokenA, wsAId, documentId)

        assertEquals("Block Editor Sample", created.path("title").asText())
        assertTrue(created.path("body_markdown").asText().contains("# 블록 문서 제목"))
        assertTrue(created.path("body_markdown").asText().contains("block-sync-keyword-initial"))
        assertEquals("doc", created.path("blocks_json").path("type").asText())
        assertEquals(userAId, created.path("created_by").asText())
        assertEquals(userAId, created.path("updated_by").asText())
        assertTrue(created.path("created_at").asText().isNotBlank())
        assertTrue(created.path("updated_at").asText().isNotBlank())

        mockMvc.perform(
            patch("/api/v1/documents/$documentId")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "version" to created.path("version").asLong(),
                            "title" to "Block Editor Sample v2",
                            "body_markdown" to "",
                            "blocks_json" to mapOf(
                                "type" to "doc",
                                "content" to listOf(
                                    mapOf(
                                        "type" to "paragraph",
                                        "content" to listOf(mapOf("type" to "text", "text" to "block-sync-keyword-patched"))
                                    ),
                                    mapOf(
                                        "type" to "horizontalRule"
                                    ),
                                    mapOf(
                                        "type" to "codeBlock",
                                        "attrs" to mapOf("language" to "kotlin"),
                                        "content" to listOf(mapOf("type" to "text", "text" to "println(\"hello\")"))
                                    )
                                )
                            )
                        )
                    )
                )
        ).andExpect(status().isNoContent)

        val patched = getDocument(tokenA, wsAId, documentId)
        assertEquals("Block Editor Sample v2", patched.path("title").asText())
        assertTrue(patched.path("body_markdown").asText().contains("block-sync-keyword-patched"))
        assertTrue(patched.path("body_markdown").asText().contains("```kotlin"))
        assertEquals(userAId, patched.path("updated_by").asText())
        assertEquals(1L, patched.path("version").asLong())

        val searchResponse = mockMvc.perform(
            get("/api/v1/documents")
                .param("q", "block-sync-keyword-patched")
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val searchItems = objectMapper.readTree(searchResponse).path("items")
        assertTrue(searchItems.any { it.path("id").asText() == documentId })
    }

    @Test
    fun `cross-tenant patch with blocks_json is denied`() {
        val createResponse = mockMvc.perform(
            post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "title" to "Tenant Isolated",
                            "body_markdown" to "initial",
                            "blocks_json" to mapOf(
                                "type" to "doc",
                                "content" to listOf(
                                    mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "tenant-a")))
                                )
                            ),
                            "source_type" to "EDITOR"
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val documentId = objectMapper.readTree(createResponse).path("id").asText()

        mockMvc.perform(
            patch("/api/v1/documents/$documentId")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenB")
                .header("X-Workspace-Id", wsBId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "version" to 0,
                            "title" to "forbidden",
                            "body_markdown" to "",
                            "blocks_json" to mapOf(
                                "type" to "doc",
                                "content" to listOf(
                                    mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "cross-tenant")))
                                )
                            )
                        )
                    )
                )
        ).andExpect(status().isConflict)
    }

    @Test
    fun `attachment presign rejects unsupported content type and oversize payload`() {
        val created = mockMvc.perform(
            post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "title" to "Attachment Policy Target",
                            "body_markdown" to "",
                            "source_type" to "EDITOR"
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val documentId = objectMapper.readTree(created).path("id").asText()

        mockMvc.perform(
            post("/api/v1/attachments/presign")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "document_id" to documentId,
                            "filename" to "payload.exe",
                            "content_type" to "application/x-msdownload",
                            "size" to 100L
                        )
                    )
                )
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/api/v1/attachments/presign")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "document_id" to documentId,
                            "filename" to "large.pdf",
                            "content_type" to "application/pdf",
                            "size" to (60L * 1024L * 1024L)
                        )
                    )
                )
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `document detail load survives attachment presign failure`() {
        val created = mockMvc.perform(
            post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $tokenA")
                .header("X-Workspace-Id", wsAId)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "title" to "Attachment Failure Resilience",
                            "body_markdown" to "본문",
                            "source_type" to "EDITOR"
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val documentId = objectMapper.readTree(created).path("id").asText()

        val brokenAttachment = attachmentRepository.create(
            workspaceId = wsAId,
            documentId = documentId,
            filename = "broken-image.png",
            contentType = "image/png",
            size = 42L,
            objectKey = "workspaces/$wsBId/attachments/$documentId/broken-image.png",
            checksumSha256 = null
        )
        attachmentRepository.updateCompleted(wsAId, brokenAttachment.id)

        val response = getDocument(tokenA, wsAId, documentId)
        val attachments = response.path("attachments")
        assertEquals(1, attachments.size())
        assertEquals("UPLOADED", attachments[0].path("status").asText())
        assertTrue(attachments[0].path("download_url").isNull, "download_url should be null when presign generation fails")
        assertEquals("Attachment Failure Resilience", response.path("title").asText())
    }

    private fun getDocument(token: String, workspaceId: String, documentId: String) = objectMapper.readTree(
        mockMvc.perform(
            get("/api/v1/documents/$documentId")
                .header("Authorization", "Bearer $token")
                .header("X-Workspace-Id", workspaceId)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
    )

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
