package com.autodoctree.api.domain.workspace

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
import com.autodoctree.api.domain.document.AuditService

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
