package com.autodoctree.api.tenant

import com.autodoctree.common.Role

data class WorkspaceContext(
    val userId: String,
    val workspaceId: String,
    val role: Role
)
