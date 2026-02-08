package com.autodoctree.api.infra

import com.autodoctree.api.tenant.WorkspaceContext
import com.autodoctree.common.Role

fun requireRole(context: WorkspaceContext, allowed: Set<Role>) {
    if (!allowed.contains(context.role)) {
        throw ForbiddenException()
    }
}

fun requireOwner(context: WorkspaceContext) {
    requireRole(context, setOf(Role.OWNER))
}

fun requireEditor(context: WorkspaceContext) {
    requireRole(context, setOf(Role.OWNER, Role.MEMBER))
}
