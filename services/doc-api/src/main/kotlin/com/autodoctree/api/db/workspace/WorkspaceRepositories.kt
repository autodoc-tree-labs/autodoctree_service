package com.autodoctree.api.db.workspace

import com.autodoctree.api.db.*
import com.autodoctree.api.infra.ConflictException
import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.UUID

@Repository
class WorkspaceRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<WorkspaceRow> { rs: ResultSet, _: Int ->
        WorkspaceRow(
            id = rs.getString("id"),
            name = rs.getString("name"),
            createdBy = rs.getString("created_by"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun create(name: String, createdBy: String): WorkspaceRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            "INSERT INTO workspaces(id, name, created_by, created_at) VALUES (?, ?, ?, ?)",
            id,
            name,
            createdBy,
            now
        )
        return WorkspaceRow(id, name, createdBy, now)
    }

    fun listByUser(userId: String): List<WorkspaceRow> = jdbcTemplate.query(
        """
        SELECT w.*
        FROM workspaces w
        JOIN memberships m ON m.workspace_id = w.id
        WHERE m.user_id = ?
        ORDER BY w.created_at
        """.trimIndent(),
        mapper,
        userId
    )

    fun findByWorkspaceAndUser(workspaceId: String, userId: String): WorkspaceRow? = jdbcTemplate.queryOneOrNull(
        """
        SELECT w.*
        FROM workspaces w
        JOIN memberships m ON m.workspace_id = w.id
        WHERE w.id = ? AND m.user_id = ?
        """.trimIndent(),
        mapper,
        workspaceId,
        userId
    )
}

@Repository
class MembershipRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<MembershipRow> { rs: ResultSet, _: Int ->
        MembershipRow(
            workspaceId = rs.getString("workspace_id"),
            userId = rs.getString("user_id"),
            role = rs.getString("role"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            email = rs.getString("email")
        )
    }

    fun create(workspaceId: String, userId: String, role: String) {
        jdbcTemplate.update(
            "INSERT INTO memberships(workspace_id, user_id, role, created_at) VALUES (?, ?, ?, ?)",
            workspaceId,
            userId,
            role,
            LocalDateTime.now()
        )
    }

    fun updateRole(workspaceId: String, userId: String, role: String) {
        jdbcTemplate.update(
            "UPDATE memberships SET role = ? WHERE workspace_id = ? AND user_id = ?",
            role,
            workspaceId,
            userId
        )
    }

    fun delete(workspaceId: String, userId: String) {
        jdbcTemplate.update(
            "DELETE FROM memberships WHERE workspace_id = ? AND user_id = ?",
            workspaceId,
            userId
        )
    }

    fun findRoleByWorkspaceAndUser(workspaceId: String, userId: String): String? {
        val rows = jdbcTemplate.queryForList(
            "SELECT role FROM memberships WHERE workspace_id = ? AND user_id = ?",
            workspaceId,
            userId
        )
        return rows.firstOrNull()?.get("role") as? String
    }

    fun listMembers(workspaceId: String): List<MembershipRow> = jdbcTemplate.query(
        """
        SELECT m.workspace_id, m.user_id, m.role, m.created_at, u.email
        FROM memberships m
        JOIN users u ON u.id = m.user_id
        WHERE m.workspace_id = ?
        ORDER BY m.created_at
        """.trimIndent(),
        mapper,
        workspaceId
    )
}

@Repository
class WorkspaceInviteRepository(private val jdbcTemplate: JdbcTemplate) {
    fun create(workspaceId: String, email: String, role: String, tokenHash: String, invitedBy: String, expiresAt: LocalDateTime): String {
        val id = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO workspace_invites(id, workspace_id, email, role, token_hash, invited_by, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            workspaceId,
            email,
            role,
            tokenHash,
            invitedBy,
            expiresAt,
            LocalDateTime.now()
        )
        return id
    }

    fun findActiveByTokenHash(tokenHash: String): Map<String, Any?>? {
        val rows = jdbcTemplate.queryForList(
            """
            SELECT *
            FROM workspace_invites
            WHERE token_hash = ?
              AND accepted_at IS NULL
              AND expires_at > ?
            """.trimIndent(),
            tokenHash,
            LocalDateTime.now()
        )
        return rows.firstOrNull()
    }

    fun markAccepted(id: String, userId: String) {
        jdbcTemplate.update(
            "UPDATE workspace_invites SET accepted_at = ?, accepted_by = ? WHERE id = ?",
            LocalDateTime.now(),
            userId,
            id
        )
    }
}

