package com.autodoctree.api.controller.feedback

import com.autodoctree.api.domain.feedback.FeedbackService
import com.autodoctree.api.tenant.WorkspaceContextResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/feedback")
class FeedbackController(
    private val feedbackService: FeedbackService,
    private val workspaceContextResolver: WorkspaceContextResolver
) {

    @PostMapping("/move")
    fun move(
        request: HttpServletRequest,
        @Valid @RequestBody body: MoveRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        feedbackService.move(context, body.documentId, body.fromNodeId, body.toNodeId, body.source)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/rename")
    fun rename(
        request: HttpServletRequest,
        @Valid @RequestBody body: RenameRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        feedbackService.rename(context, body.nodeId, body.oldLabel, body.newLabel)
        return ResponseEntity.noContent().build()
    }
}

data class MoveRequest(
    @field:NotBlank val documentId: String,
    val fromNodeId: String?,
    @field:NotBlank val toNodeId: String,
    val source: String? = null
)

data class RenameRequest(
    @field:NotBlank val nodeId: String,
    val oldLabel: String?,
    @field:NotBlank val newLabel: String
)
