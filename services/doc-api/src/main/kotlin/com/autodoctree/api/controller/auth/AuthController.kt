package com.autodoctree.api.controller.auth

import com.autodoctree.api.domain.auth.AuthService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/register/request-code")
    fun requestRegisterCode(@Valid @RequestBody request: RegisterCodeRequest): Map<String, Long> {
        val expiresInSeconds = authService.requestRegistrationCode(request.email, request.password)
        return mapOf("expires_in_seconds" to expiresInSeconds)
    }

    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): Map<String, String> {
        val tokens = authService.register(request.email, request.verificationCode)
        return mapOf(
            "access_token" to tokens.accessToken,
            "refresh_token" to tokens.refreshToken
        )
    }

    @PostMapping("/register/verify")
    fun verifyRegister(@Valid @RequestBody request: RegisterRequest): Map<String, String> {
        val tokens = authService.register(request.email, request.verificationCode)
        return mapOf(
            "access_token" to tokens.accessToken,
            "refresh_token" to tokens.refreshToken
        )
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): Map<String, String> {
        val tokens = authService.login(request.email, request.password)
        return mapOf(
            "access_token" to tokens.accessToken,
            "refresh_token" to tokens.refreshToken
        )
    }

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): Map<String, String> {
        val tokens = authService.refresh(request.refreshToken)
        return mapOf(
            "access_token" to tokens.accessToken,
            "refresh_token" to tokens.refreshToken
        )
    }

    @PostMapping("/logout")
    fun logout(@Valid @RequestBody request: RefreshRequest): ResponseEntity<Void> {
        authService.logout(request.refreshToken)
        return ResponseEntity.noContent().build()
    }
}

data class LoginRequest(
    @field:Email val email: String,
    @field:NotBlank val password: String
)

data class RegisterCodeRequest(
    @field:Email val email: String,
    @field:NotBlank @field:Size(min = 8, max = 128) val password: String
)

data class RegisterRequest(
    @field:Email val email: String,
    @field:NotBlank val verificationCode: String
)

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String
)
