package com.autodoctree.api.controller

import com.autodoctree.api.domain.AdminService
import com.autodoctree.api.domain.AttachmentService
import com.autodoctree.api.domain.AuthService
import com.autodoctree.api.domain.DocumentService
import com.autodoctree.api.domain.FeedbackService
import com.autodoctree.api.domain.QuestionService
import com.autodoctree.api.domain.SearchService
import com.autodoctree.api.domain.TreeService
import com.autodoctree.api.domain.TreeViewType
import com.autodoctree.api.domain.WorkspaceService
import com.autodoctree.api.security.CurrentUserProvider
import com.autodoctree.api.tenant.WorkspaceContextResolver
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
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
@RequestMapping("/api/v1")
class HealthController {
    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf("status" to "OK")
}

@RestController
class MetricsController(
    private val meterRegistry: MeterRegistry
) {
    @GetMapping("/metrics")
    fun metrics(): Map<String, Any> {
        val names = meterRegistry.meters.map { it.id.name }.distinct().sorted()
        return mapOf("meters" to names)
    }
}

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): Map<String, String> {
        val tokens = authService.login(request.email, request.password)
        return mapOf(
            "access_token" to tokens.accessToken,
            "refresh_token" to tokens.refreshToken
        )
    }

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): Map<String, String> {
        val tokens = authService.refresh(request.refreshToken)
        return mapOf(
            "access_token" to tokens.accessToken,
            "refresh_token" to tokens.refreshToken
        )
    }

    @PostMapping("/logout")
    fun logout(@Valid @RequestBody request: RefreshRequest): ResponseEntity<Void> {
        authService.logout(request.refreshToken)
        return ResponseEntity.noContent().build()
    }
}

@RestController
@RequestMapping("/api/v1/workspaces")
class WorkspaceController(
    private val currentUserProvider: CurrentUserProvider,
    private val workspaceService: WorkspaceService,
    private val workspaceContextResolver: WorkspaceContextResolver
) {

    @GetMapping
    fun listWorkspaces(): Map<String, Any> {
        val user = currentUserProvider.currentUser()
        return mapOf("items" to workspaceService.listWorkspaces(user.id))
    }

    @PostMapping
    fun createWorkspace(@Valid @RequestBody request: CreateWorkspaceRequest): Map<String, String> {
        val user = currentUserProvider.currentUser()
        return workspaceService.createWorkspace(user.id, request.name)
    }

    @GetMapping("/{workspaceId}/members")
    fun listMembers(
        request: HttpServletRequest,
        @PathVariable workspaceId: String
    ): Map<String, Any> {
        val context = workspaceContextResolver.resolve(request)
        return mapOf("items" to workspaceService.listMembers(context, workspaceId))
    }

    @PostMapping("/{workspaceId}/members")
    fun addMember(
        request: HttpServletRequest,
        @PathVariable workspaceId: String,
        @Valid @RequestBody body: AddMemberRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        workspaceService.addMember(context, workspaceId, body.email, body.role)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @PatchMapping("/{workspaceId}/members/{userId}")
    fun updateMemberRole(
        request: HttpServletRequest,
        @PathVariable workspaceId: String,
        @PathVariable userId: String,
        @Valid @RequestBody body: UpdateMemberRoleRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        workspaceService.updateMemberRole(context, workspaceId, userId, body.role)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    fun deleteMember(
        request: HttpServletRequest,
        @PathVariable workspaceId: String,
        @PathVariable userId: String
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        workspaceService.removeMember(context, workspaceId, userId)
        return ResponseEntity.noContent().build()
    }
}

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
        return documentService.createDocument(context, body.title, body.bodyMarkdown, body.sourceType)
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

    @PatchMapping("/{documentId}")
    fun patchDocument(
        request: HttpServletRequest,
        @PathVariable documentId: String,
        @Valid @RequestBody body: PatchDocumentRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        documentService.patchDocument(context, documentId, body.version, body.title, body.bodyMarkdown)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{documentId}")
    fun deleteDocument(request: HttpServletRequest, @PathVariable documentId: String): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        documentService.deleteDocument(context, documentId)
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
}

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

@RestController
@RequestMapping("/api/v1/search")
class SearchController(
    private val workspaceContextResolver: WorkspaceContextResolver,
    private val searchService: SearchService
) {

    @GetMapping
    fun search(
        request: HttpServletRequest,
        @RequestParam q: String,
        @RequestParam(defaultValue = "bm25") mode: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return searchService.search(context, q, page, size)
    }
}

@RestController
@RequestMapping("/api/v1/tree")
class TreeController(
    private val treeService: TreeService,
    private val workspaceContextResolver: WorkspaceContextResolver
) {

    @GetMapping("/active")
    fun active(
        request: HttpServletRequest,
        @RequestParam(required = false) view: String?
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return treeService.getActiveTree(context, TreeViewType.fromApi(view))
    }

    @GetMapping("/snapshots")
    fun snapshots(
        request: HttpServletRequest,
        @RequestParam(required = false) view: String?
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return treeService.listSnapshots(context, TreeViewType.fromApi(view))
    }

    @PostMapping("/rebuild")
    fun rebuild(
        request: HttpServletRequest,
        @Valid @RequestBody body: RebuildRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return treeService.requestRebuild(
            context = context,
            mode = body.mode,
            viewType = TreeViewType.fromApi(body.view)
        )
    }

    @PostMapping("/snapshots/{snapshotId}/activate")
    fun activate(
        request: HttpServletRequest,
        @PathVariable snapshotId: String
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        treeService.activateSnapshot(context, snapshotId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/nodes/{nodeId}/lock")
    fun lockNode(
        request: HttpServletRequest,
        @PathVariable nodeId: String,
        @Valid @RequestBody body: LockNodeRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        treeService.lockNode(context, nodeId, body.locked)
        return ResponseEntity.noContent().build()
    }
}

@RestController
@RequestMapping("/api/v1/trees")
class TreesController(
    private val treeService: TreeService,
    private val workspaceContextResolver: WorkspaceContextResolver
) {

    @GetMapping
    fun treeByView(
        request: HttpServletRequest,
        @RequestParam(required = false) view: String?
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return treeService.getTreeByView(context, TreeViewType.fromApi(view))
    }
}

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

@RestController
@RequestMapping("/api/v1/questions")
class QuestionController(
    private val questionService: QuestionService,
    private val workspaceContextResolver: WorkspaceContextResolver
) {

    @GetMapping
    fun listQuestions(
        request: HttpServletRequest,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "20") limit: Int
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return questionService.listQuestions(context, status, limit)
    }

    @PostMapping("/{questionId}/answer")
    fun answerQuestion(
        request: HttpServletRequest,
        @PathVariable questionId: String,
        @Valid @RequestBody body: AnswerQuestionRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return questionService.answerQuestion(context, questionId, body.answer)
    }
}

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val adminService: AdminService,
    private val questionService: QuestionService,
    private val workspaceContextResolver: WorkspaceContextResolver
) {

    @GetMapping("/jobs")
    fun jobs(
        request: HttpServletRequest,
        @RequestParam(required = false, name = "document_id") documentId: String?
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.listJobs(context, documentId)
    }

    @PostMapping("/jobs/retry")
    fun retry(
        request: HttpServletRequest,
        @Valid @RequestBody body: RetryRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        adminService.retryStage(context, body.documentId, body.stage)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/audit")
    fun audit(
        request: HttpServletRequest,
        @RequestParam(required = false, name = "type") type: String?,
        @RequestParam(required = false, name = "actor_user_id") actorUserId: String?,
        @RequestParam(required = false, name = "q") query: String?,
        @RequestParam(required = false, name = "sort", defaultValue = "desc") sort: String,
        @RequestParam(required = false, name = "limit", defaultValue = "100") limit: Int
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.listAudit(context, type, actorUserId, query, sort, limit)
    }

    @GetMapping("/tree/debug/neighbors")
    fun debugNeighbors(
        request: HttpServletRequest,
        @RequestParam(name = "document_id") documentId: String
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.debugNeighbors(context, documentId)
    }

    @GetMapping("/tree/debug/docs/{documentId}")
    fun debugDocument(
        request: HttpServletRequest,
        @PathVariable documentId: String,
        @RequestParam(name = "top_n", defaultValue = "8") topN: Int
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.debugDocument(context, documentId, topN)
    }

    @GetMapping("/tree/debug/clusters/{clusterId}")
    fun debugCluster(
        request: HttpServletRequest,
        @PathVariable clusterId: String
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.debugCluster(context, clusterId)
    }

    @GetMapping("/tree/debug/rebuilds/{snapshotId}")
    fun debugRebuild(
        request: HttpServletRequest,
        @PathVariable snapshotId: String
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.debugRebuild(context, snapshotId)
    }

    @GetMapping("/tree/debug/cluster-stats")
    fun clusterStats(request: HttpServletRequest): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.clusterStats(context)
    }

    @GetMapping("/tree/policy")
    fun getTreePolicy(request: HttpServletRequest): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.getTreePolicy(context)
    }

    @PatchMapping("/tree/policy")
    fun updateTreePolicy(
        request: HttpServletRequest,
        @Valid @RequestBody body: UpdateTreePolicyRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.updateTreePolicy(
            context = context,
            autoThreshold = body.autoThreshold,
            recommendThreshold = body.recommendThreshold,
            quarantineEnabled = body.quarantineEnabled,
            rerankerEnabled = body.rerankerEnabled
        )
    }

    @GetMapping("/tree/rules")
    fun listUserRules(request: HttpServletRequest): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.listUserRules(context)
    }

    @PostMapping("/tree/rules")
    fun createUserRule(
        request: HttpServletRequest,
        @Valid @RequestBody body: CreateUserRuleRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.createUserRule(
            context = context,
            ruleType = body.ruleType,
            ruleValue = body.ruleValue,
            nodeId = body.nodeId,
            ruleEffect = body.ruleEffect
        )
    }

    @PatchMapping("/tree/rules/{ruleId}")
    fun updateUserRule(
        request: HttpServletRequest,
        @PathVariable ruleId: String,
        @Valid @RequestBody body: UpdateUserRuleRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.updateUserRule(
            context = context,
            ruleId = ruleId,
            ruleType = body.ruleType,
            ruleValue = body.ruleValue,
            nodeId = body.nodeId,
            ruleEffect = body.ruleEffect
        )
    }

    @PostMapping("/tree/rules/preview")
    fun previewUserRule(
        request: HttpServletRequest,
        @Valid @RequestBody body: PreviewUserRuleRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return adminService.previewUserRule(
            context = context,
            documentId = body.documentId,
            ruleType = body.ruleType,
            ruleValue = body.ruleValue,
            nodeId = body.nodeId,
            ruleEffect = body.ruleEffect
        )
    }

    @DeleteMapping("/tree/rules/{ruleId}")
    fun deleteUserRule(
        request: HttpServletRequest,
        @PathVariable ruleId: String
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        adminService.deleteUserRule(context, ruleId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/tree/questions/analytics")
    fun questionAnalytics(request: HttpServletRequest): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return questionService.analytics(context)
    }

    @PatchMapping("/tree/questions/control")
    fun updateQuestionControl(
        request: HttpServletRequest,
        @Valid @RequestBody body: UpdateQuestionControlRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return questionService.updateControl(context, body.enabled)
    }

    @PostMapping("/tree/questions/expire")
    fun expireQuestions(request: HttpServletRequest): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return questionService.expireOpen(context)
    }

    @PostMapping("/tree/questions/generate")
    fun generateQuestions(request: HttpServletRequest): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return questionService.generateNow(context)
    }
}

data class LoginRequest(
    @field:Email val email: String,
    @field:NotBlank val password: String
)

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String
)

data class CreateWorkspaceRequest(
    @field:NotBlank val name: String
)

data class AddMemberRequest(
    @field:Email val email: String,
    @field:NotBlank val role: String
)

data class UpdateMemberRoleRequest(
    @field:NotBlank val role: String
)

data class CreateDocumentRequest(
    @field:NotBlank val title: String,
    val bodyMarkdown: String?,
    @field:NotBlank val sourceType: String
)

data class PatchDocumentRequest(
    @field:NotNull val version: Long,
    @field:NotBlank val title: String,
    val bodyMarkdown: String?
)

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

data class RebuildRequest(
    @field:NotBlank val mode: String = "DEBOUNCED",
    val view: String? = null
)

data class LockNodeRequest(
    @field:NotNull val locked: Boolean
)

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

data class AnswerQuestionRequest(
    @field:NotBlank val answer: String
)

data class RetryRequest(
    @field:NotBlank val documentId: String,
    @field:NotBlank val stage: String
)

data class UpdateTreePolicyRequest(
    val autoThreshold: Double,
    val recommendThreshold: Double,
    val quarantineEnabled: Boolean,
    val rerankerEnabled: Boolean
)

data class CreateUserRuleRequest(
    @field:NotBlank val ruleType: String,
    @field:NotBlank val ruleValue: String,
    @field:NotBlank val nodeId: String,
    val ruleEffect: String? = null
)

data class UpdateUserRuleRequest(
    @field:NotBlank val ruleType: String,
    @field:NotBlank val ruleValue: String,
    @field:NotBlank val nodeId: String,
    val ruleEffect: String? = null
)

data class PreviewUserRuleRequest(
    @field:NotBlank val documentId: String,
    @field:NotBlank val ruleType: String,
    @field:NotBlank val ruleValue: String,
    @field:NotBlank val nodeId: String,
    val ruleEffect: String? = null
)

data class UpdateQuestionControlRequest(
    @field:NotNull val enabled: Boolean
)
