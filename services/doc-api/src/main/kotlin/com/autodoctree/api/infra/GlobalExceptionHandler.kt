package com.autodoctree.api.infra

import com.autodoctree.contracts.ErrorBody
import com.autodoctree.contracts.ErrorEnvelope
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ResponseEntity<ErrorEnvelope> {
        return ResponseEntity.status(ex.status).body(
            ErrorEnvelope(
                ErrorBody(
                    code = ex.code,
                    message = ex.message,
                    trace_id = MDC.get("trace_id"),
                    details = ex.details
                )
            )
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorEnvelope> {
        val detail = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorEnvelope(
                ErrorBody(
                    code = "VALIDATION_ERROR",
                    message = "Validation failed",
                    trace_id = MDC.get("trace_id"),
                    details = detail
                )
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorEnvelope> {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorEnvelope(
                ErrorBody(
                    code = "INTERNAL_ERROR",
                    message = "Unexpected error",
                    trace_id = MDC.get("trace_id")
                )
            )
        )
    }
}
