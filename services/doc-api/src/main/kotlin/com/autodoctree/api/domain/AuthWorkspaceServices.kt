package com.autodoctree.api.domain

import com.autodoctree.api.config.AuthProperties
import com.autodoctree.api.db.MembershipRepository
import com.autodoctree.api.db.RegistrationVerificationCodeRepository
import com.autodoctree.api.db.RefreshTokenRepository
import com.autodoctree.api.db.UserRepository
import com.autodoctree.api.db.WorkspaceInviteRepository
import com.autodoctree.api.db.WorkspaceRepository
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

@Service
class WorkspaceService(
    private val workspaceRepository: WorkspaceRepository,
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authProperties: AuthProperties,
    private val auditService: AuditService,
    private val workspaceInviteRepository: WorkspaceInviteRepository
) {
    @Transactional
    fun createWorkspace(userId: String, name: String): Map<String, String> {
        val workspace = workspaceRepository.create(name, userId)
        membershipRepository.create(workspace.id, userId, "OWNER")
        auditService.write(
            workspaceId = workspace.id,
            actorUserId = userId,
            action = "workspace.created",
            payload = mapOf(
                "workspace_id" to workspace.id,
                "name" to name
            )
        )
        return mapOf("id" to workspace.id, "name" to workspace.name)
    }

    fun listWorkspaces(userId: String): List<Map<String, String>> {
        val workspaces = workspaceRepository.listByUser(userId)
        return workspaces.mapNotNull { workspace ->
            val role = membershipRepository.findRoleByWorkspaceAndUser(workspace.id, userId) ?: return@mapNotNull null
            mapOf(
                "id" to workspace.id,
                "name" to workspace.name,
                "role" to role
            )
        }
    }

    fun listMembers(context: WorkspaceContext, workspaceId: String): List<Map<String, String>> {
        if (context.workspaceId != workspaceId) {
            throw ForbiddenException()
        }
        val members = membershipRepository.listMembers(workspaceId)
        return members.map {
            mapOf(
                "user_id" to it.userId,
                "email" to (it.email ?: ""),
                "role" to it.role
            )
        }
    }

    @Transactional
    fun addMember(context: WorkspaceContext, workspaceId: String, email: String, role: String) {
        if (context.workspaceId != workspaceId) {
            throw ForbiddenException()
        }
        requireOwner(context)
        val user = userRepository.findByEmail(email)
            ?: userRepository.create(email, passwordEncoder.encode("password"))

        if (membershipRepository.findRoleByWorkspaceAndUser(workspaceId, user.id) == null) {
            membershipRepository.create(workspaceId, user.id, role)
        } else {
            membershipRepository.updateRole(workspaceId, user.id, role)
        }
        auditService.write(
            workspaceId = workspaceId,
            actorUserId = context.userId,
            action = "membership.changed",
            payload = mapOf(
                "target_user_id" to user.id,
                "email" to email,
                "role" to role
            )
        )
    }



    @Transactional
    fun createInvite(context: WorkspaceContext, workspaceId: String, email: String, role: String): Map<String, String> {
        if (context.workspaceId != workspaceId) throw ForbiddenException()
        requireOwner(context)
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank()) {
            throw BadRequestException("Email is required")
        }
        val token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val tokenHash = sha256(token)
        workspaceInviteRepository.create(
            workspaceId = workspaceId,
            email = normalizedEmail,
            role = role,
            tokenHash = tokenHash,
            invitedBy = context.userId,
            expiresAt = LocalDateTime.now().plusDays(7)
        )
        auditService.write(workspaceId, context.userId, "workspace.invite.created", mapOf("email" to normalizedEmail, "role" to role))
        return mapOf("invite_token" to token)
    }

    @Transactional
    fun acceptInvite(userId: String, token: String): Map<String, String> {
        val normalizedToken = token.trim()
        if (normalizedToken.isBlank()) {
            throw BadRequestException("Invite token is required")
        }
        val invite = workspaceInviteRepository.findActiveByTokenHash(sha256(normalizedToken)) ?: throw NotFoundException()
        val workspaceId = invite["workspace_id"] as String
        val role = invite["role"] as String
        if (authProperties.invite.requireEmailMatch) {
            val invitedEmail = (invite["email"] as? String)?.trim()?.lowercase()
                ?: throw BadRequestException("Invite email is invalid")
            val userEmail = userRepository.findById(userId)?.email?.trim()?.lowercase()
                ?: throw UnauthorizedException("Invalid credentials")
            if (invitedEmail != userEmail) {
                throw ForbiddenException("Invite token is bound to another email")
            }
        }
        if (membershipRepository.findRoleByWorkspaceAndUser(workspaceId, userId) == null) {
            membershipRepository.create(workspaceId, userId, role)
        }
        workspaceInviteRepository.markAccepted(invite["id"] as String, userId)
        auditService.write(workspaceId, userId, "workspace.invite.accepted", mapOf("role" to role))
        return mapOf("workspace_id" to workspaceId, "role" to role)
    }
    @Transactional
    fun updateMemberRole(context: WorkspaceContext, workspaceId: String, userId: String, role: String) {
        if (context.workspaceId != workspaceId) {
            throw ForbiddenException()
        }
        requireOwner(context)
        val exists = membershipRepository.findRoleByWorkspaceAndUser(workspaceId, userId)
            ?: throw NotFoundException()
        if (exists == role) {
            return
        }
        membershipRepository.updateRole(workspaceId, userId, role)
        auditService.write(
            workspaceId = workspaceId,
            actorUserId = context.userId,
            action = "membership.role_updated",
            payload = mapOf(
                "target_user_id" to userId,
                "role" to role
            )
        )
    }

    @Transactional
    fun removeMember(context: WorkspaceContext, workspaceId: String, userId: String) {
        if (context.workspaceId != workspaceId) {
            throw ForbiddenException()
        }
        requireOwner(context)
        if (context.userId == userId) {
            throw ForbiddenException("Cannot remove yourself from workspace")
        }
        val exists = membershipRepository.findRoleByWorkspaceAndUser(workspaceId, userId)
            ?: throw NotFoundException()
        membershipRepository.delete(workspaceId, userId)
        auditService.write(
            workspaceId = workspaceId,
            actorUserId = context.userId,
            action = "membership.removed",
            payload = mapOf(
                "target_user_id" to userId,
                "previous_role" to exists
            )
        )
    }
}
