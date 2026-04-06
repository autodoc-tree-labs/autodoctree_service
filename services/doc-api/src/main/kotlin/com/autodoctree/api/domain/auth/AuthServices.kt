package com.autodoctree.api.domain.auth

import com.autodoctree.api.config.AuthProperties
import com.autodoctree.api.db.workspace.MembershipRepository
import com.autodoctree.api.db.auth.RegistrationVerificationCodeRepository
import com.autodoctree.api.db.auth.RefreshTokenRepository
import com.autodoctree.api.db.auth.UserRepository
import com.autodoctree.api.db.workspace.WorkspaceInviteRepository
import com.autodoctree.api.db.workspace.WorkspaceRepository
import com.autodoctree.api.infra.BadRequestException
import com.autodoctree.api.infra.ConflictException
import com.autodoctree.api.infra.ForbiddenException
import com.autodoctree.api.infra.NotFoundException
import com.autodoctree.api.infra.ServiceUnavailableException
import com.autodoctree.api.infra.UnauthorizedException
import com.autodoctree.api.infra.requireOwner
import com.autodoctree.api.infra.sha256
import com.autodoctree.api.security.AuthTokens
import com.autodoctree.api.security.AuthUser
import com.autodoctree.api.security.JwtService
import com.autodoctree.api.tenant.WorkspaceContext
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.UUID

@Service
class SeedDataService(
    private val userRepository: UserRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val membershipRepository: MembershipRepository,
    private val passwordEncoder: PasswordEncoder
) {
    @Transactional
    fun seedIfNeeded() {
        val ownerEmail = "owner@autodoc.local"
        val owner = userRepository.findByEmail(ownerEmail)
            ?: userRepository.create(ownerEmail, passwordEncoder.encode("password"))

        if (workspaceRepository.listByUser(owner.id).isEmpty()) {
            val workspace = workspaceRepository.create("Personal", owner.id)
            membershipRepository.create(workspace.id, owner.id, "OWNER")
        }

        val memberEmail = "member@autodoc.local"
        val member = userRepository.findByEmail(memberEmail)
            ?: userRepository.create(memberEmail, passwordEncoder.encode("password"))

        val ownerWorkspace = workspaceRepository.listByUser(owner.id).first()
        if (membershipRepository.findRoleByWorkspaceAndUser(ownerWorkspace.id, member.id) == null) {
            membershipRepository.create(ownerWorkspace.id, member.id, "MEMBER")
        }

        val viewerEmail = "viewer@autodoc.local"
        val viewer = userRepository.findByEmail(viewerEmail)
            ?: userRepository.create(viewerEmail, passwordEncoder.encode("password"))
        if (membershipRepository.findRoleByWorkspaceAndUser(ownerWorkspace.id, viewer.id) == null) {
            membershipRepository.create(ownerWorkspace.id, viewer.id, "VIEWER")
        }
    }
}

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val registrationVerificationCodeRepository: RegistrationVerificationCodeRepository,
    private val passwordEncoder: PasswordEncoder,
    private val mailSender: JavaMailSender?,
    private val jwtService: JwtService,
    private val authProperties: AuthProperties,
    private val workspaceRepository: WorkspaceRepository,
    private val membershipRepository: MembershipRepository
) {
    private val secureRandom = SecureRandom()

    @Transactional
    fun requestRegistrationCode(email: String, password: String): Long {
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank()) {
            throw BadRequestException("Email is required")
        }
        if (password.length < 8) {
            throw BadRequestException("Password must be at least 8 characters")
        }
        if (userRepository.findByEmail(normalizedEmail) != null) {
            throw ConflictException("Email already exists")
        }

        val ttlSeconds = authProperties.registerVerification.codeTtlSeconds.coerceAtLeast(60)
        val verificationCode = "%06d".format(secureRandom.nextInt(1_000_000))
        val codeHash = sha256(verificationCode)
        val passwordHash = passwordEncoder.encode(password)

        registrationVerificationCodeRepository.createOrReplace(
            email = normalizedEmail,
            passwordHash = passwordHash,
            codeHash = codeHash,
            expiresAt = LocalDateTime.now().plusSeconds(ttlSeconds)
        )
        sendRegistrationCodeEmail(
            email = normalizedEmail,
            verificationCode = verificationCode,
            ttlSeconds = ttlSeconds
        )
        return ttlSeconds
    }

    @Transactional
    fun register(email: String, verificationCode: String): AuthTokens {
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank()) {
            throw BadRequestException("Email is required")
        }
        val normalizedCode = verificationCode.trim()
        if (normalizedCode.isBlank()) {
            throw BadRequestException("Verification code is required")
        }

        val pending = registrationVerificationCodeRepository.findActiveByEmail(normalizedEmail)
            ?: throw BadRequestException("Verification code is invalid or expired")

        val maxAttempts = authProperties.registerVerification.maxAttempts.coerceAtLeast(1)
        if (pending.attemptCount >= maxAttempts) {
            throw BadRequestException("Verification attempts exceeded")
        }
        if (pending.codeHash != sha256(normalizedCode)) {
            registrationVerificationCodeRepository.incrementAttempt(pending.id)
            throw BadRequestException("Verification code is invalid or expired")
        }
        if (userRepository.findByEmail(normalizedEmail) != null) {
            registrationVerificationCodeRepository.markConsumed(pending.id)
            throw ConflictException("Email already exists")
        }

        val user = userRepository.create(normalizedEmail, pending.passwordHash)
        val workspacePrefix = normalizedEmail.substringBefore("@").ifBlank { "My" }
        val workspaceName = "$workspacePrefix Workspace"
        val workspace = workspaceRepository.create(workspaceName, user.id)
        membershipRepository.create(workspace.id, user.id, "OWNER")
        registrationVerificationCodeRepository.markConsumed(pending.id)

        return issueTokens(AuthUser(user.id, user.email))
    }

    @Transactional
    fun login(email: String, password: String): AuthTokens {
        val normalizedEmail = email.trim().lowercase()
        val user = userRepository.findByEmail(normalizedEmail) ?: throw UnauthorizedException("Invalid credentials")
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw UnauthorizedException("Invalid credentials")
        }
        return issueTokens(AuthUser(user.id, user.email))
    }

    @Transactional
    fun refresh(refreshToken: String): AuthTokens {
        val hashed = sha256(refreshToken)
        val tokenRow = refreshTokenRepository.findActiveByHash(hashed) ?: throw UnauthorizedException("Invalid token")
        refreshTokenRepository.revokeByHash(hashed)
        val userId = tokenRow["user_id"] as String
        val user = userRepository.findById(userId) ?: throw UnauthorizedException("Invalid token")
        return issueTokens(AuthUser(user.id, user.email))
    }

    @Transactional
    fun logout(refreshToken: String) {
        refreshTokenRepository.revokeByHash(sha256(refreshToken))
    }

    private fun sendRegistrationCodeEmail(email: String, verificationCode: String, ttlSeconds: Long) {
        val configuredSender = authProperties.registerVerification.senderEmail.trim()
        if (configuredSender.isBlank()) {
            throw ServiceUnavailableException("Email verification delivery failed")
        }
        val effectiveMailSender = mailSender ?: throw ServiceUnavailableException("Email verification delivery failed")
        val message = SimpleMailMessage().apply {
            from = configuredSender
            setTo(email)
            subject = authProperties.registerVerification.subject
            text = buildString {
                appendLine("AutoDoc signup verification code")
                appendLine()
                appendLine("Code: $verificationCode")
                appendLine("Expires in: ${((ttlSeconds + 59) / 60)} minutes")
            }
        }
        try {
            effectiveMailSender.send(message)
        } catch (_: MailException) {
            throw ServiceUnavailableException("Email verification delivery failed")
        }
    }

    private fun issueTokens(user: AuthUser): AuthTokens {
        val accessToken = jwtService.generateAccessToken(user)
        val refreshToken = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        refreshTokenRepository.create(
            userId = user.id,
            tokenHash = sha256(refreshToken),
            expiresAt = LocalDateTime.now().plusSeconds(authProperties.refreshTokenTtlSeconds)
        )
        return AuthTokens(accessToken, refreshToken)
    }
}
