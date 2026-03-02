package com.autodoctree.api.infra

open class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val details: Map<String, Any?> = emptyMap()
) : RuntimeException(message)

class BadRequestException(message: String, details: Map<String, Any?> = emptyMap()) :
    ApiException(400, "BAD_REQUEST", message, details)

class UnauthorizedException(message: String = "Unauthorized") :
    ApiException(401, "UNAUTHORIZED", message)

class ForbiddenException(message: String = "Access denied") :
    ApiException(403, "TENANT_FORBIDDEN", message)

class NotFoundException(message: String = "Not found") :
    ApiException(404, "NOT_FOUND", message)

class ConflictException(message: String) :
    ApiException(409, "CONFLICT", message)

class ServiceUnavailableException(message: String) :
    ApiException(503, "SERVICE_UNAVAILABLE", message)
