package com.autodoctree.api.domain

import com.autodoctree.api.config.AuthProperties
import com.autodoctree.api.db.MembershipRepository
import com.autodoctree.api.db.RefreshTokenRepository
import com.autodoctree.api.db.UserRepository
import com.autodoctree.api.db.WorkspaceRepository
import com.autodoctree.api.infra.NotFoundException
import com.autodoctree.api.infra.ForbiddenException
import com.autodoctree.api.infra.UnauthorizedException
import com.autodoctree.api.infra.requireOwner
import com.autodoctree.api.infra.sha256
import com.autodoctree.api.security.AuthTokens
import com.autodoctree.api.security.AuthUser
import com.autodoctree.api.security.JwtService
import com.autodoctree.api.tenant.WorkspaceContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val authProperties: AuthProperties
) {
    @Transactional
    fun login(email: String, password: String): AuthTokens {
        val user = userRepository.findByEmail(email) ?: throw UnauthorizedException("Invalid credentials")
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
    private val auditService: AuditService
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
