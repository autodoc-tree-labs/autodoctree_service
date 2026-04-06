package com.autodoctree.api.controller.admin

import com.autodoctree.api.domain.admin.AdminService
import com.autodoctree.api.domain.question.QuestionService
import com.autodoctree.api.tenant.WorkspaceContextResolver
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
