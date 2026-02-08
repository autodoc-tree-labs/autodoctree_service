package com.autodoctree.api.tenant

import com.autodoctree.api.infra.ForbiddenException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
class WorkspaceContextResolver {
    fun resolve(request: HttpServletRequest): WorkspaceContext {
        val context = request.getAttribute(WorkspaceContextFilter.WORKSPACE_CONTEXT_REQUEST_KEY)
        if (context !is WorkspaceContext) {
            throw ForbiddenException()
        }
        return context
    }
}
