package com.autodoctree.api.tenant

import com.autodoctree.api.db.MembershipRepository
import com.autodoctree.api.security.AuthUser
import com.autodoctree.common.Role
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class WorkspaceContextFilter(
    private val membershipRepository: MembershipRepository,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry
) : OncePerRequestFilter() {

    private val missingScopeCounter = meterRegistry.counter("security.tenant_scope_missing_total")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return !(path.startsWith("/api/v1/documents") ||
            path.startsWith("/api/v1/attachments") ||
            path.startsWith("/api/v1/search") ||
            path.startsWith("/api/v1/tree") ||
            path.startsWith("/api/v1/trees") ||
            path.startsWith("/api/v1/questions") ||
            path.startsWith("/api/v1/feedback") ||
            path.startsWith("/api/v1/admin") ||
            path.contains("/members"))
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal
        if (principal !is AuthUser) {
            writeError(response, 403, "TENANT_FORBIDDEN", "Access denied")
            return
        }

        val workspaceId = request.getHeader("X-Workspace-Id")?.trim().orEmpty()
        if (workspaceId.isBlank()) {
            missingScopeCounter.increment()
            writeError(response, 400, "BAD_REQUEST", "X-Workspace-Id header is required")
            return
        }

        val membership = membershipRepository.findRoleByWorkspaceAndUser(workspaceId, principal.id)
            ?: run {
                writeError(response, 403, "TENANT_FORBIDDEN", "Access denied")
                return
            }

        val role = Role.valueOf(membership)
        val context = WorkspaceContext(
            userId = principal.id,
            workspaceId = workspaceId,
            role = role
        )
        request.setAttribute(WORKSPACE_CONTEXT_REQUEST_KEY, context)
        MDC.put("workspace_id", workspaceId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove("workspace_id")
        }
    }

    private fun writeError(response: HttpServletResponse, status: Int, code: String, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf(
                    "error" to mapOf(
                        "code" to code,
                        "message" to message,
                        "trace_id" to MDC.get("trace_id"),
                        "details" to null
                    )
                )
            )
        )
    }

    companion object {
        const val WORKSPACE_CONTEXT_REQUEST_KEY = "workspaceContext"
    }
}
