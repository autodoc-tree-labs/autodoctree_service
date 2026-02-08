package com.autodoctree.api.infra

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestTracingFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val requestId = request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val traceId = request.getHeader("X-Trace-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

        MDC.put("request_id", requestId)
        MDC.put("trace_id", traceId)

        response.setHeader("X-Request-Id", requestId)
        response.setHeader("X-Trace-Id", traceId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove("request_id")
            MDC.remove("trace_id")
        }
    }
}
