package com.autodoctree.api.security

import com.autodoctree.api.infra.UnauthorizedException
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import io.jsonwebtoken.JwtException
import org.slf4j.MDC
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class AuthFilter(
    private val jwtService: JwtService,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path.startsWith("/api/v1/auth/login") ||
            path.startsWith("/api/v1/auth/refresh") ||
            path.startsWith("/api/v1/auth/logout") ||
            path.startsWith("/api/v1/health") ||
            path.startsWith("/actuator") ||
            path.startsWith("/metrics")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authorization = request.getHeader("Authorization")
        if (authorization.isNullOrBlank() || !authorization.startsWith("Bearer ")) {
            writeUnauthorized(response)
            return
        }
        val token = authorization.removePrefix("Bearer ").trim()
        try {
            val user = jwtService.parseAccessToken(token)
            val authentication = UsernamePasswordAuthenticationToken(
                user,
                token,
                listOf(SimpleGrantedAuthority("ROLE_USER"))
            )
            SecurityContextHolder.getContext().authentication = authentication
        } catch (ex: JwtException) {
            SecurityContextHolder.clearContext()
            writeUnauthorized(response)
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun writeUnauthorized(response: HttpServletResponse) {
        response.status = 401
        response.contentType = "application/json"
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf(
                    "error" to mapOf(
                        "code" to UnauthorizedException().code,
                        "message" to "Unauthorized",
                        "trace_id" to MDC.get("trace_id"),
                        "details" to null
                    )
                )
            )
        )
    }
}
