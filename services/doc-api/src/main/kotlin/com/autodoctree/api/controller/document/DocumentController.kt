package com.autodoctree.api.controller.document

import com.autodoctree.api.domain.document.DocumentService
import com.autodoctree.api.domain.tree.TreeService
import com.autodoctree.api.tenant.WorkspaceContextResolver
import com.fasterxml.jackson.databind.JsonNode
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/documents")
class DocumentController(
    private val documentService: DocumentService,
    private val treeService: TreeService,
    private val workspaceContextResolver: WorkspaceContextResolver
) {

    @PostMapping
    fun createDocument(
        request: HttpServletRequest,
        @Valid @RequestBody body: CreateDocumentRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return documentService.createDocument(
            context = context,
            title = body.title,
            bodyMarkdown = body.bodyMarkdown,
            blocksJson = body.blocksJson,
            sourceType = body.sourceType,
            parentDocumentId = body.parentDocumentId
        )
    }

    @GetMapping("/favorites")
    fun listFavorites(request: HttpServletRequest): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return documentService.listFavorites(context)
    }

    @PostMapping("/{documentId}/favorite")
    fun addFavorite(
        request: HttpServletRequest,
        @PathVariable documentId: String
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        documentService.addFavorite(context, documentId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{documentId}/favorite")
    fun removeFavorite(
        request: HttpServletRequest,
        @PathVariable documentId: String
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        documentService.removeFavorite(context, documentId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{documentId}")
    fun getDocument(request: HttpServletRequest, @PathVariable documentId: String): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return documentService.getDocument(context, documentId)
    }

    @GetMapping
    fun listDocuments(
        request: HttpServletRequest,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, name = "q") query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return documentService.listDocuments(context, status, query, page, size)
    }

    @GetMapping("/sidebar")
    fun listSidebarDocuments(request: HttpServletRequest): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return documentService.listSidebar(context)
    }

    @GetMapping("/library")
    fun listLibraryDocuments(
        request: HttpServletRequest,
        @RequestParam(required = false, name = "q") query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "100") size: Int
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return documentService.listLibrary(context, query, page, size)
    }

    @PostMapping("/library/personal-top")
    fun movePersonalTop(
        request: HttpServletRequest,
        @Valid @RequestBody body: UpdatePersonalTopRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return documentService.movePersonalTop(context, body.documentIds)
    }

    @PostMapping("/library/bulk-trash")
    fun bulkTrashRoots(
        request: HttpServletRequest,
        @Valid @RequestBody body: BulkTrashRootDocumentsRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return documentService.bulkTrashRoots(context, body.documentIds)
    }

    @GetMapping("/trash")
    fun listTrashDocuments(
        request: HttpServletRequest,
        @RequestParam(required = false, name = "q") query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return documentService.listTrash(context, query, page, size)
    }

    @PatchMapping("/{documentId}")
    fun patchDocument(
        request: HttpServletRequest,
        @PathVariable documentId: String,
        @Valid @RequestBody body: PatchDocumentRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        documentService.patchDocument(
            context = context,
            documentId = documentId,
            expectedVersion = body.version,
            title = body.title,
            bodyMarkdown = body.bodyMarkdown,
            blocksJson = body.blocksJson
        )
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{documentId}/move")
    fun moveDocument(
        request: HttpServletRequest,
        @PathVariable documentId: String,
        @Valid @RequestBody body: MoveDocumentRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        documentService.moveDocument(context, documentId, body.parentDocumentId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{documentId}")
    fun deleteDocument(request: HttpServletRequest, @PathVariable documentId: String): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        documentService.deleteDocument(context, documentId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{documentId}/restore")
    fun restoreDocument(
        request: HttpServletRequest,
        @PathVariable documentId: String
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        documentService.restoreDocument(context, documentId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{documentId}/explain")
    fun explainDocument(request: HttpServletRequest, @PathVariable documentId: String): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return treeService.explain(context, documentId)
    }

    @PostMapping("/{documentId}/explain/accept")
    fun acceptExplainDocument(request: HttpServletRequest, @PathVariable documentId: String): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        treeService.acceptExplain(context, documentId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{documentId}/pipeline/retry")
    fun retryPipelineStage(
        request: HttpServletRequest,
        @PathVariable documentId: String,
        @Valid @RequestBody body: RetryPipelineStageRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        documentService.retryPipelineStage(context, documentId, body.stage)
        return ResponseEntity.noContent().build()
    }
}

data class CreateDocumentRequest(
    @field:NotBlank val title: String,
    val bodyMarkdown: String?,
    val blocksJson: JsonNode? = null,
    @field:NotBlank val sourceType: String,
    val parentDocumentId: String? = null
)

data class PatchDocumentRequest(
    @field:NotNull val version: Long,
    @field:NotBlank val title: String,
    val bodyMarkdown: String?,
    val blocksJson: JsonNode? = null
)

data class MoveDocumentRequest(
    val parentDocumentId: String? = null
)

data class UpdatePersonalTopRequest(
    val documentIds: List<String> = emptyList()
)

data class BulkTrashRootDocumentsRequest(
    val documentIds: List<String> = emptyList()
)

data class RetryPipelineStageRequest(
    @field:NotBlank val stage: String
)
