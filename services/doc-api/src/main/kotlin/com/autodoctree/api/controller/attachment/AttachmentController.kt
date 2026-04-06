package com.autodoctree.api.controller.attachment

import com.autodoctree.api.domain.attachment.AttachmentService
import com.autodoctree.api.tenant.WorkspaceContextResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/attachments")
class AttachmentController(
    private val attachmentService: AttachmentService,
    private val workspaceContextResolver: WorkspaceContextResolver
) {

    @PostMapping("/presign")
    fun presign(
        request: HttpServletRequest,
        @Valid @RequestBody body: PresignRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return attachmentService.presign(
            context = context,
            documentId = body.documentId,
            filename = body.filename,
            contentType = body.contentType,
            size = body.size,
            checksumSha256 = body.checksumSha256
        )
    }

    @PostMapping("/complete")
    fun complete(
        request: HttpServletRequest,
        @Valid @RequestBody body: CompleteAttachmentRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        attachmentService.complete(context, body.attachmentId)
        return ResponseEntity.noContent().build()
    }
}

data class PresignRequest(
    @field:NotBlank val documentId: String,
    @field:NotBlank val filename: String,
    @field:NotBlank val contentType: String,
    @field:Positive val size: Long,
    val checksumSha256: String? = null
)

data class CompleteAttachmentRequest(
    @field:NotBlank val attachmentId: String
)
