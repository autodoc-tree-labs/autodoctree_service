package com.autodoctree.api.security

data class AuthUser(
    val id: String,
    val email: String
)

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String
)
