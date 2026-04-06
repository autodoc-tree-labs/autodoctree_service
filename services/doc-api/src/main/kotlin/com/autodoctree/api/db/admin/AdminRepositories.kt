package com.autodoctree.api.db.admin

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
class AuditLogRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<AuditLogRow> { rs: ResultSet, _: Int ->
        AuditLogRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            actorUserId = rs.getString("actor_user_id"),
            action = rs.getString("action"),
            payloadJson = rs.getString("payload_json"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun insert(workspaceId: String, actorUserId: String, action: String, payloadJson: String) {
        jdbcTemplate.update(
            """
            INSERT INTO audit_log(id, workspace_id, actor_user_id, action, payload_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID().toString(),
            workspaceId,
            actorUserId,
            action,
            payloadJson,
            LocalDateTime.now()
        )
    }

    fun listByWorkspace(workspaceId: String, type: String?): List<AuditLogRow> {
        return listByWorkspace(
            workspaceId = workspaceId,
            type = type,
            actorUserId = null,
            query = null,
            sort = "desc",
            limit = 300
        )
    }

    fun listByWorkspace(
        workspaceId: String,
        type: String?,
        actorUserId: String?,
        query: String?,
        sort: String?,
        limit: Int
    ): List<AuditLogRow> {
        val args = mutableListOf<Any>(workspaceId)
        val sql = StringBuilder("SELECT * FROM audit_log WHERE workspace_id = ?")
        if (!type.isNullOrBlank()) {
            sql.append(" AND action = ?")
            args.add(type.trim())
        }
        if (!actorUserId.isNullOrBlank()) {
            sql.append(" AND actor_user_id = ?")
            args.add(actorUserId.trim())
        }
        if (!query.isNullOrBlank()) {
            val like = "%${query.trim().lowercase()}%"
            sql.append(" AND (LOWER(action) LIKE ? OR LOWER(actor_user_id) LIKE ? OR LOWER(payload_json) LIKE ?)")
            args.add(like)
            args.add(like)
            args.add(like)
        }
        val sortDirection = if (sort.equals("asc", ignoreCase = true)) "ASC" else "DESC"
        sql.append(" ORDER BY created_at $sortDirection, id $sortDirection LIMIT ?")
        args.add(limit.coerceIn(1, 500))
        return jdbcTemplate.query(sql.toString(), mapper, *args.toTypedArray())
    }
}

@Repository
class PaletteHistoryRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<PaletteHistoryRow> { rs: ResultSet, _: Int ->
        PaletteHistoryRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            userId = rs.getString("user_id"),
            eventType = rs.getString("event_type"),
            queryText = rs.getString("query_text"),
            documentId = rs.getString("document_id"),
            commandKey = rs.getString("command_key"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun insert(workspaceId: String, userId: String, eventType: String, queryText: String?, documentId: String?, commandKey: String?) {
        jdbcTemplate.update(
            """
            INSERT INTO palette_history(id, workspace_id, user_id, event_type, query_text, document_id, command_key, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID().toString(),
            workspaceId,
            userId,
            eventType,
            queryText,
            documentId,
            commandKey,
            LocalDateTime.now()
        )
    }

    fun list(workspaceId: String, userId: String, limit: Int): List<PaletteHistoryRow> = jdbcTemplate.query(
        """
        SELECT *
        FROM palette_history
        WHERE workspace_id = ? AND user_id = ?
        ORDER BY created_at DESC
        LIMIT ?
        """.trimIndent(),
        mapper,
        workspaceId,
        userId,
        limit.coerceIn(1, 200)
    )
}

