package com.autodoctree.api.security

import com.autodoctree.api.infra.UnauthorizedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Component
class CurrentUserProvider {
    fun currentUser(): AuthUser {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        if (principal !is AuthUser) {
            throw UnauthorizedException()
        }
        return principal
    }
}
