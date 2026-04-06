package com.autodoctree.api.controller.workspace

import com.autodoctree.api.domain.workspace.WorkspaceService
import com.autodoctree.api.security.CurrentUserProvider
import com.autodoctree.api.tenant.WorkspaceContextResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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

    @PostMapping("/{workspaceId}/invites")
    fun createInvite(
        request: HttpServletRequest,
        @PathVariable workspaceId: String,
        @Valid @RequestBody body: CreateWorkspaceInviteRequest
    ): Map<String, String> {
        val context = workspaceContextResolver.resolve(request)
        return workspaceService.createInvite(context, workspaceId, body.email, body.role)
    }

    @PostMapping("/invites/accept")
    fun acceptInvite(@Valid @RequestBody body: AcceptWorkspaceInviteRequest): Map<String, String> {
        val user = currentUserProvider.currentUser()
        return workspaceService.acceptInvite(user.id, body.token)
    }
}

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

data class CreateWorkspaceInviteRequest(
    @field:Email val email: String,
    @field:NotBlank val role: String
)

data class AcceptWorkspaceInviteRequest(
    @field:NotBlank val token: String
)
