package com.autodoctree.contracts

data class ErrorEnvelope(
    val error: ErrorBody
)

data class ErrorBody(
    val code: String,
    val message: String,
    val trace_id: String?,
    val details: Map<String, Any?>? = null
)
