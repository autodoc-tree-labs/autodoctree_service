package com.autodoctree.api.db

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

private fun <T> JdbcTemplate.queryOneOrNull(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): T? {
    val list = query(sql, rowMapper, *args)
    return list.firstOrNull()
}

data class UserRow(
    val id: String,
    val email: String,
    val passwordHash: String,
    val createdAt: LocalDateTime
)

data class WorkspaceRow(
    val id: String,
    val name: String,
    val createdBy: String,
    val createdAt: LocalDateTime
)

data class MembershipRow(
    val workspaceId: String,
    val userId: String,
    val role: String,
    val createdAt: LocalDateTime,
    val email: String? = null
)

data class DocumentRow(
    val id: String,
    val workspaceId: String,
    val title: String,
    val bodyMarkdown: String?,
    val bodyText: String?,
    val sourceType: String,
    val status: String,
    val version: Long,
    val deleted: Boolean,
    val createdBy: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class PipelineStatusRow(
    val workspaceId: String,
    val documentId: String,
    val ingestStatus: StageStatus,
    val embedStatus: StageStatus,
    val indexStatus: StageStatus,
    val treeStatus: StageStatus,
    val failureReason: String?,
    val updatedAt: LocalDateTime
)

data class AttachmentRow(
    val id: String,
    val workspaceId: String,
    val documentId: String,
    val filename: String,
    val contentType: String,
    val size: Long,
    val objectKey: String,
    val checksumSha256: String?,
    val status: String,
    val createdAt: LocalDateTime,
    val completedAt: LocalDateTime?
)

data class SectionRow(
    val id: String,
    val workspaceId: String,
    val documentId: String,
    val ord: Int,
    val heading: String?,
    val chunkText: String,
    val qualityFlags: String?,
    val createdAt: LocalDateTime
)

data class EmbeddingRow(
    val id: String,
    val workspaceId: String,
    val documentId: String,
    val targetType: String,
    val targetId: String,
    val vectorJson: String,
    val modelVersion: String,
    val createdAt: LocalDateTime
)

data class OutboxEventRow(
    val id: String,
    val workspaceId: String,
    val documentId: String?,
    val eventType: String,
    val payloadJson: String,
    val status: String,
    val retryCount: Int,
    val availableAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class StageExecutionRow(
    val id: String,
    val workspaceId: String,
    val documentId: String,
    val stage: Stage,
    val inputHash: String,
    val modelVersion: String,
    val status: StageStatus,
    val message: String?,
    val retries: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class TreeSnapshotRow(
    val id: String,
    val workspaceId: String,
    val status: String,
    val movedRatio: Double,
    val churnCount: Int,
    val createdAt: LocalDateTime,
    val activatedAt: LocalDateTime?,
    val activatedBy: String?
)

data class TreeNodeRow(
    val id: String,
    val workspaceId: String,
    val snapshotId: String,
    val parentId: String?,
    val label: String,
    val depth: Int,
    val locked: Boolean,
    val createdAt: LocalDateTime
)

data class TreeMembershipRow(
    val id: String,
    val workspaceId: String,
    val snapshotId: String,
    val nodeId: String,
    val documentId: String,
    val rationaleJson: String,
    val createdAt: LocalDateTime
)

data class FeedbackEventRow(
    val id: String,
    val workspaceId: String,
    val userId: String,
    val eventType: String,
    val payloadJson: String,
    val createdAt: LocalDateTime
)

data class AuditLogRow(
    val id: String,
    val workspaceId: String,
    val actorUserId: String,
    val action: String,
    val payloadJson: String,
    val createdAt: LocalDateTime
)

@Repository
class UserRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<UserRow> { rs: ResultSet, _: Int ->
        UserRow(
            id = rs.getString("id"),
            email = rs.getString("email"),
            passwordHash = rs.getString("password_hash"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun create(email: String, passwordHash: String): UserRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO users(id, email, password_hash, created_at)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            id,
            email,
            passwordHash,
            now
        )
        return UserRow(id, email, passwordHash, now)
    }

    fun findByEmail(email: String): UserRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM users WHERE email = ?",
        mapper,
        email
    )

    fun findById(id: String): UserRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM users WHERE id = ?",
        mapper,
        id
    )
}

@Repository
class RefreshTokenRepository(private val jdbcTemplate: JdbcTemplate) {

    fun create(userId: String, tokenHash: String, expiresAt: LocalDateTime): String {
        val id = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO refresh_tokens(id, user_id, token_hash, expires_at, revoked_at, created_at)
            VALUES (?, ?, ?, ?, NULL, ?)
            """.trimIndent(),
            id,
            userId,
            tokenHash,
            expiresAt,
            LocalDateTime.now()
        )
        return id
    }

    fun findActiveByHash(tokenHash: String): Map<String, Any?>? {
        val rows = jdbcTemplate.queryForList(
            """
            SELECT id, user_id, expires_at, revoked_at
            FROM refresh_tokens
            WHERE token_hash = ?
            """.trimIndent(),
            tokenHash
        )
        return rows.firstOrNull()?.takeIf {
            val revokedAt = it["revoked_at"]
            val expiresAt = (it["expires_at"] as java.sql.Timestamp).toLocalDateTime()
            revokedAt == null && expiresAt.isAfter(LocalDateTime.now())
        }
    }

    fun revokeByHash(tokenHash: String) {
        jdbcTemplate.update(
            "UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
            LocalDateTime.now(),
            tokenHash
        )
    }
}

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
class DocumentRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<DocumentRow> { rs: ResultSet, _: Int ->
        DocumentRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            title = rs.getString("title"),
            bodyMarkdown = rs.getString("body_markdown"),
            bodyText = rs.getString("body_text"),
            sourceType = rs.getString("source_type"),
            status = rs.getString("status"),
            version = rs.getLong("version"),
            deleted = rs.getBoolean("deleted"),
            createdBy = rs.getString("created_by"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    fun create(
        workspaceId: String,
        title: String,
        bodyMarkdown: String?,
        bodyText: String?,
        sourceType: String,
        createdBy: String
    ): DocumentRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO documents(
                id, workspace_id, title, body_markdown, body_text, source_type,
                status, version, deleted, created_by, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, false, ?, ?, ?)
            """.trimIndent(),
            id,
            workspaceId,
            title,
            bodyMarkdown,
            bodyText,
            sourceType,
            "PROCESSING",
            createdBy,
            now,
            now
        )
        return DocumentRow(
            id = id,
            workspaceId = workspaceId,
            title = title,
            bodyMarkdown = bodyMarkdown,
            bodyText = bodyText,
            sourceType = sourceType,
            status = "PROCESSING",
            version = 0,
            deleted = false,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now
        )
    }

    fun findByWorkspaceAndId(workspaceId: String, documentId: String): DocumentRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM documents WHERE workspace_id = ? AND id = ? AND deleted = false",
        mapper,
        workspaceId,
        documentId
    )

    fun listByWorkspace(
        workspaceId: String,
        status: String?,
        query: String?,
        page: Int,
        size: Int
    ): List<DocumentRow> {
        val sql = StringBuilder("SELECT * FROM documents WHERE workspace_id = ? AND deleted = false")
        val args = mutableListOf<Any>(workspaceId)
        if (!status.isNullOrBlank()) {
            sql.append(" AND status = ?")
            args.add(status)
        }
        if (!query.isNullOrBlank()) {
            sql.append(" AND (LOWER(title) LIKE ? OR LOWER(COALESCE(body_text, '')) LIKE ?)")
            val pattern = "%${query.lowercase()}%"
            args.add(pattern)
            args.add(pattern)
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?")
        args.add(size)
        args.add(page * size)
        return jdbcTemplate.query(sql.toString(), mapper, *args.toTypedArray())
    }

    fun countByWorkspace(workspaceId: String, status: String?, query: String?): Long {
        val sql = StringBuilder("SELECT COUNT(*) FROM documents WHERE workspace_id = ? AND deleted = false")
        val args = mutableListOf<Any>(workspaceId)
        if (!status.isNullOrBlank()) {
            sql.append(" AND status = ?")
            args.add(status)
        }
        if (!query.isNullOrBlank()) {
            sql.append(" AND (LOWER(title) LIKE ? OR LOWER(COALESCE(body_text, '')) LIKE ?)")
            val pattern = "%${query.lowercase()}%"
            args.add(pattern)
            args.add(pattern)
        }
        return jdbcTemplate.queryForObject(sql.toString(), Long::class.java, *args.toTypedArray()) ?: 0
    }

    fun update(
        workspaceId: String,
        documentId: String,
        expectedVersion: Long,
        title: String,
        bodyMarkdown: String?
    ) {
        val updated = jdbcTemplate.update(
            """
            UPDATE documents
            SET title = ?, body_markdown = ?, body_text = ?, version = version + 1, updated_at = ?
            WHERE workspace_id = ? AND id = ? AND version = ? AND deleted = false
            """.trimIndent(),
            title,
            bodyMarkdown,
            bodyMarkdown,
            LocalDateTime.now(),
            workspaceId,
            documentId,
            expectedVersion
        )
        if (updated == 0) {
            throw ConflictException("Document version conflict")
        }
    }

    fun softDelete(workspaceId: String, documentId: String) {
        jdbcTemplate.update(
            "UPDATE documents SET deleted = true, status = ?, updated_at = ? WHERE workspace_id = ? AND id = ?",
            "DELETED",
            LocalDateTime.now(),
            workspaceId,
            documentId
        )
    }

    fun updateBodyText(workspaceId: String, documentId: String, bodyText: String, status: String) {
        jdbcTemplate.update(
            "UPDATE documents SET body_text = ?, status = ?, updated_at = ? WHERE workspace_id = ? AND id = ?",
            bodyText,
            status,
            LocalDateTime.now(),
            workspaceId,
            documentId
        )
    }

    fun updateStatus(workspaceId: String, documentId: String, status: String) {
        jdbcTemplate.update(
            "UPDATE documents SET status = ?, updated_at = ? WHERE workspace_id = ? AND id = ?",
            status,
            LocalDateTime.now(),
            workspaceId,
            documentId
        )
    }

    fun searchByWorkspace(workspaceId: String, query: String, size: Int, offset: Int): List<DocumentRow> {
        val pattern = "%${query.lowercase()}%"
        return jdbcTemplate.query(
            """
            SELECT *
            FROM documents
            WHERE workspace_id = ?
              AND deleted = false
              AND (LOWER(title) LIKE ? OR LOWER(COALESCE(body_text, '')) LIKE ?)
            ORDER BY updated_at DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            mapper,
            workspaceId,
            pattern,
            pattern,
            size,
            offset
        )
    }

    fun listWorkspaceDocuments(workspaceId: String): List<DocumentRow> = jdbcTemplate.query(
        "SELECT * FROM documents WHERE workspace_id = ? AND deleted = false ORDER BY updated_at DESC",
        mapper,
        workspaceId
    )
}

@Repository
class PipelineStatusRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<PipelineStatusRow> { rs: ResultSet, _: Int ->
        PipelineStatusRow(
            workspaceId = rs.getString("workspace_id"),
            documentId = rs.getString("document_id"),
            ingestStatus = StageStatus.valueOf(rs.getString("ingest_status")),
            embedStatus = StageStatus.valueOf(rs.getString("embed_status")),
            indexStatus = StageStatus.valueOf(rs.getString("index_status")),
            treeStatus = StageStatus.valueOf(rs.getString("tree_status")),
            failureReason = rs.getString("failure_reason"),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    fun create(workspaceId: String, documentId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO pipeline_status(
                workspace_id, document_id, ingest_status, embed_status, index_status, tree_status, failure_reason, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
            """.trimIndent(),
            workspaceId,
            documentId,
            StageStatus.PENDING.name,
            StageStatus.PENDING.name,
            StageStatus.PENDING.name,
            StageStatus.PENDING.name,
            LocalDateTime.now()
        )
    }

    fun findByWorkspaceAndDocument(workspaceId: String, documentId: String): PipelineStatusRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM pipeline_status WHERE workspace_id = ? AND document_id = ?",
        mapper,
        workspaceId,
        documentId
    )

    fun updateStage(workspaceId: String, documentId: String, stage: Stage, status: StageStatus, failureReason: String? = null) {
        val column = when (stage) {
            Stage.INGEST -> "ingest_status"
            Stage.EMBED -> "embed_status"
            Stage.INDEX -> "index_status"
            Stage.TREE -> "tree_status"
        }
        jdbcTemplate.update(
            """
            UPDATE pipeline_status
            SET $column = ?, failure_reason = ?, updated_at = ?
            WHERE workspace_id = ? AND document_id = ?
            """.trimIndent(),
            status.name,
            failureReason,
            LocalDateTime.now(),
            workspaceId,
            documentId
        )
    }
}

@Repository
class AttachmentRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<AttachmentRow> { rs: ResultSet, _: Int ->
        AttachmentRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            documentId = rs.getString("document_id"),
            filename = rs.getString("filename"),
            contentType = rs.getString("content_type"),
            size = rs.getLong("size"),
            objectKey = rs.getString("object_key"),
            checksumSha256 = rs.getString("checksum_sha256"),
            status = rs.getString("status"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            completedAt = rs.getTimestamp("completed_at")?.toLocalDateTime()
        )
    }

    fun create(
        workspaceId: String,
        documentId: String,
        filename: String,
        contentType: String,
        size: Long,
        objectKey: String,
        checksumSha256: String?
    ): AttachmentRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO attachments(
                id, workspace_id, document_id, filename, content_type, size,
                object_key, checksum_sha256, status, created_at, completed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
            """.trimIndent(),
            id,
            workspaceId,
            documentId,
            filename,
            contentType,
            size,
            objectKey,
            checksumSha256,
            "PRESIGNED",
            now
        )
        return AttachmentRow(
            id,
            workspaceId,
            documentId,
            filename,
            contentType,
            size,
            objectKey,
            checksumSha256,
            "PRESIGNED",
            now,
            null
        )
    }

    fun findByWorkspaceAndId(workspaceId: String, attachmentId: String): AttachmentRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM attachments WHERE workspace_id = ? AND id = ?",
        mapper,
        workspaceId,
        attachmentId
    )

    fun updateCompleted(workspaceId: String, attachmentId: String) {
        jdbcTemplate.update(
            "UPDATE attachments SET status = ?, completed_at = ? WHERE workspace_id = ? AND id = ?",
            "UPLOADED",
            LocalDateTime.now(),
            workspaceId,
            attachmentId
        )
    }

    fun listByWorkspaceAndDocument(workspaceId: String, documentId: String): List<AttachmentRow> = jdbcTemplate.query(
        "SELECT * FROM attachments WHERE workspace_id = ? AND document_id = ? ORDER BY created_at",
        mapper,
        workspaceId,
        documentId
    )
}

@Repository
class DocumentSectionRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<SectionRow> { rs: ResultSet, _: Int ->
        SectionRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            documentId = rs.getString("document_id"),
            ord = rs.getInt("ord"),
            heading = rs.getString("heading"),
            chunkText = rs.getString("chunk_text"),
            qualityFlags = rs.getString("quality_flags"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun replaceSections(workspaceId: String, documentId: String, sections: List<SectionRow>) {
        jdbcTemplate.update(
            "DELETE FROM document_sections WHERE workspace_id = ? AND document_id = ?",
            workspaceId,
            documentId
        )
        sections.forEach { section ->
            jdbcTemplate.update(
                """
                INSERT INTO document_sections(
                    id, workspace_id, document_id, ord, heading, chunk_text, quality_flags, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                section.id,
                section.workspaceId,
                section.documentId,
                section.ord,
                section.heading,
                section.chunkText,
                section.qualityFlags,
                section.createdAt
            )
        }
    }

    fun listByWorkspaceAndDocument(workspaceId: String, documentId: String): List<SectionRow> = jdbcTemplate.query(
        "SELECT * FROM document_sections WHERE workspace_id = ? AND document_id = ? ORDER BY ord",
        mapper,
        workspaceId,
        documentId
    )
}

@Repository
class EmbeddingRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<EmbeddingRow> { rs: ResultSet, _: Int ->
        EmbeddingRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            documentId = rs.getString("document_id"),
            targetType = rs.getString("target_type"),
            targetId = rs.getString("target_id"),
            vectorJson = rs.getString("vector_json"),
            modelVersion = rs.getString("model_version"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun upsert(
        workspaceId: String,
        documentId: String,
        targetType: String,
        targetId: String,
        vectorJson: String,
        modelVersion: String
    ) {
        val now = LocalDateTime.now()
        try {
            jdbcTemplate.update(
                """
                INSERT INTO embeddings(
                    id, workspace_id, document_id, target_type, target_id, vector_json, model_version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID().toString(),
                workspaceId,
                documentId,
                targetType,
                targetId,
                vectorJson,
                modelVersion,
                now
            )
        } catch (_: DuplicateKeyException) {
            jdbcTemplate.update(
                """
                UPDATE embeddings
                SET vector_json = ?, created_at = ?
                WHERE workspace_id = ? AND target_type = ? AND target_id = ? AND model_version = ?
                """.trimIndent(),
                vectorJson,
                now,
                workspaceId,
                targetType,
                targetId,
                modelVersion
            )
        }
    }

    fun findDocEmbedding(workspaceId: String, documentId: String, modelVersion: String): EmbeddingRow? = jdbcTemplate.queryOneOrNull(
        """
        SELECT * FROM embeddings
        WHERE workspace_id = ? AND document_id = ? AND target_type = 'DOCUMENT' AND model_version = ?
        ORDER BY created_at DESC
        LIMIT 1
        """.trimIndent(),
        mapper,
        workspaceId,
        documentId,
        modelVersion
    )

    fun listDocEmbeddings(workspaceId: String, modelVersion: String): List<EmbeddingRow> = jdbcTemplate.query(
        """
        SELECT * FROM embeddings
        WHERE workspace_id = ? AND target_type = 'DOCUMENT' AND model_version = ?
        ORDER BY created_at DESC
        """.trimIndent(),
        mapper,
        workspaceId,
        modelVersion
    )
}

@Repository
class OutboxRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<OutboxEventRow> { rs: ResultSet, _: Int ->
        OutboxEventRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            documentId = rs.getString("document_id"),
            eventType = rs.getString("event_type"),
            payloadJson = rs.getString("payload_json"),
            status = rs.getString("status"),
            retryCount = rs.getInt("retry_count"),
            availableAt = rs.getTimestamp("available_at").toLocalDateTime(),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    fun insert(workspaceId: String, documentId: String?, eventType: String, payloadJson: String): String {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO outbox_event(
                id, workspace_id, document_id, event_type, payload_json, status,
                retry_count, available_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
            """.trimIndent(),
            id,
            workspaceId,
            documentId,
            eventType,
            payloadJson,
            "PENDING",
            now,
            now,
            now
        )
        return id
    }

    fun fetchBatch(limit: Int): List<OutboxEventRow> = jdbcTemplate.query(
        """
        SELECT * FROM outbox_event
        WHERE status IN ('PENDING', 'RETRY')
          AND available_at <= ?
        ORDER BY created_at ASC
        LIMIT ?
        """.trimIndent(),
        mapper,
        LocalDateTime.now(),
        limit
    )

    fun markProcessing(eventId: String) {
        jdbcTemplate.update(
            "UPDATE outbox_event SET status = ?, updated_at = ? WHERE id = ?",
            "PROCESSING",
            LocalDateTime.now(),
            eventId
        )
    }

    fun markDone(eventId: String) {
        jdbcTemplate.update(
            "UPDATE outbox_event SET status = ?, updated_at = ? WHERE id = ?",
            "DONE",
            LocalDateTime.now(),
            eventId
        )
    }

    fun markRetry(eventId: String, retryCount: Int, nextAvailableAt: LocalDateTime) {
        jdbcTemplate.update(
            """
            UPDATE outbox_event
            SET status = ?, retry_count = ?, available_at = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            "RETRY",
            retryCount,
            nextAvailableAt,
            LocalDateTime.now(),
            eventId
        )
    }

    fun markDlq(eventId: String) {
        jdbcTemplate.update(
            "UPDATE outbox_event SET status = ?, updated_at = ? WHERE id = ?",
            "DLQ",
            LocalDateTime.now(),
            eventId
        )
    }

    fun listByWorkspace(workspaceId: String, documentId: String?): List<OutboxEventRow> {
        val args = mutableListOf<Any>(workspaceId)
        val sql = StringBuilder("SELECT * FROM outbox_event WHERE workspace_id = ?")
        if (!documentId.isNullOrBlank()) {
            sql.append(" AND document_id = ?")
            args.add(documentId)
        }
        sql.append(" ORDER BY created_at DESC LIMIT 200")
        return jdbcTemplate.query(sql.toString(), mapper, *args.toTypedArray())
    }
}

@Repository
class DlqRepository(private val jdbcTemplate: JdbcTemplate) {
    fun insert(outboxEventId: String, workspaceId: String, reason: String, payloadJson: String) {
        jdbcTemplate.update(
            """
            INSERT INTO dlq_event(id, outbox_event_id, workspace_id, reason, payload_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID().toString(),
            outboxEventId,
            workspaceId,
            reason,
            payloadJson,
            LocalDateTime.now()
        )
    }
}

@Repository
class StageExecutionRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<StageExecutionRow> { rs: ResultSet, _: Int ->
        StageExecutionRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            documentId = rs.getString("document_id"),
            stage = Stage.valueOf(rs.getString("stage")),
            inputHash = rs.getString("input_hash"),
            modelVersion = rs.getString("model_version"),
            status = StageStatus.valueOf(rs.getString("status")),
            message = rs.getString("message"),
            retries = rs.getInt("retries"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    fun tryStart(workspaceId: String, documentId: String, stage: Stage, inputHash: String, modelVersion: String): Boolean {
        val now = LocalDateTime.now()
        return try {
            jdbcTemplate.update(
                """
                INSERT INTO stage_execution(
                    id, workspace_id, document_id, stage, input_hash, model_version,
                    status, message, retries, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 0, ?, ?)
                """.trimIndent(),
                UUID.randomUUID().toString(),
                workspaceId,
                documentId,
                stage.name,
                inputHash,
                modelVersion,
                StageStatus.RUNNING.name,
                now,
                now
            )
            true
        } catch (_: DuplicateKeyException) {
            val reopened = jdbcTemplate.update(
                """
                UPDATE stage_execution
                SET status = ?, message = NULL, updated_at = ?
                WHERE workspace_id = ? AND document_id = ? AND stage = ? AND input_hash = ? AND model_version = ? AND status = ?
                """.trimIndent(),
                StageStatus.RUNNING.name,
                now,
                workspaceId,
                documentId,
                stage.name,
                inputHash,
                modelVersion,
                StageStatus.FAILED.name
            )
            reopened > 0
        }
    }

    fun markDone(workspaceId: String, documentId: String, stage: Stage, inputHash: String, modelVersion: String) {
        jdbcTemplate.update(
            """
            UPDATE stage_execution
            SET status = ?, updated_at = ?
            WHERE workspace_id = ? AND document_id = ? AND stage = ? AND input_hash = ? AND model_version = ?
            """.trimIndent(),
            StageStatus.DONE.name,
            LocalDateTime.now(),
            workspaceId,
            documentId,
            stage.name,
            inputHash,
            modelVersion
        )
    }

    fun markFailed(
        workspaceId: String,
        documentId: String,
        stage: Stage,
        inputHash: String,
        modelVersion: String,
        message: String
    ) {
        jdbcTemplate.update(
            """
            UPDATE stage_execution
            SET status = ?, message = ?, retries = retries + 1, updated_at = ?
            WHERE workspace_id = ? AND document_id = ? AND stage = ? AND input_hash = ? AND model_version = ?
            """.trimIndent(),
            StageStatus.FAILED.name,
            message.take(500),
            LocalDateTime.now(),
            workspaceId,
            documentId,
            stage.name,
            inputHash,
            modelVersion
        )
    }

    fun listByWorkspace(workspaceId: String, documentId: String?): List<StageExecutionRow> {
        val args = mutableListOf<Any>(workspaceId)
        val sql = StringBuilder("SELECT * FROM stage_execution WHERE workspace_id = ?")
        if (!documentId.isNullOrBlank()) {
            sql.append(" AND document_id = ?")
            args.add(documentId)
        }
        sql.append(" ORDER BY created_at DESC LIMIT 200")
        return jdbcTemplate.query(sql.toString(), mapper, *args.toTypedArray())
    }
}

@Repository
class TreeRepository(private val jdbcTemplate: JdbcTemplate) {
    private val snapshotMapper = RowMapper<TreeSnapshotRow> { rs: ResultSet, _: Int ->
        TreeSnapshotRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            status = rs.getString("status"),
            movedRatio = rs.getDouble("moved_ratio"),
            churnCount = rs.getInt("churn_count"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            activatedAt = rs.getTimestamp("activated_at")?.toLocalDateTime(),
            activatedBy = rs.getString("activated_by")
        )
    }

    private val nodeMapper = RowMapper<TreeNodeRow> { rs: ResultSet, _: Int ->
        TreeNodeRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            snapshotId = rs.getString("snapshot_id"),
            parentId = rs.getString("parent_id"),
            label = rs.getString("label"),
            depth = rs.getInt("depth"),
            locked = rs.getBoolean("locked"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    private val membershipMapper = RowMapper<TreeMembershipRow> { rs: ResultSet, _: Int ->
        TreeMembershipRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            snapshotId = rs.getString("snapshot_id"),
            nodeId = rs.getString("node_id"),
            documentId = rs.getString("document_id"),
            rationaleJson = rs.getString("rationale_json"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun createSnapshot(workspaceId: String, status: String, movedRatio: Double, churnCount: Int): TreeSnapshotRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO tree_snapshot(
                id, workspace_id, status, moved_ratio, churn_count, created_at, activated_at, activated_by
            ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)
            """.trimIndent(),
            id,
            workspaceId,
            status,
            movedRatio,
            churnCount,
            now
        )
        return TreeSnapshotRow(id, workspaceId, status, movedRatio, churnCount, now, null, null)
    }

    fun findActiveSnapshot(workspaceId: String): TreeSnapshotRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM tree_snapshot WHERE workspace_id = ? AND status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1",
        snapshotMapper,
        workspaceId
    )

    fun listLockedNodesInActiveSnapshot(workspaceId: String): List<TreeNodeRow> {
        val active = findActiveSnapshot(workspaceId) ?: return emptyList()
        return jdbcTemplate.query(
            "SELECT * FROM tree_node WHERE workspace_id = ? AND snapshot_id = ? AND locked = true",
            nodeMapper,
            workspaceId,
            active.id
        )
    }

    fun findSnapshotByWorkspace(workspaceId: String, snapshotId: String): TreeSnapshotRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM tree_snapshot WHERE workspace_id = ? AND id = ?",
        snapshotMapper,
        workspaceId,
        snapshotId
    )

    fun listSnapshots(workspaceId: String): List<TreeSnapshotRow> = jdbcTemplate.query(
        "SELECT * FROM tree_snapshot WHERE workspace_id = ? ORDER BY created_at DESC LIMIT 30",
        snapshotMapper,
        workspaceId
    )

    fun markAllSnapshotsRecommended(workspaceId: String) {
        jdbcTemplate.update(
            "UPDATE tree_snapshot SET status = 'RECOMMENDED' WHERE workspace_id = ? AND status = 'ACTIVE'",
            workspaceId
        )
    }

    fun activateSnapshot(workspaceId: String, snapshotId: String, actorUserId: String) {
        jdbcTemplate.update(
            "UPDATE tree_snapshot SET status = 'RECOMMENDED' WHERE workspace_id = ? AND status = 'ACTIVE'",
            workspaceId
        )
        jdbcTemplate.update(
            """
            UPDATE tree_snapshot
            SET status = 'ACTIVE', activated_at = ?, activated_by = ?
            WHERE workspace_id = ? AND id = ?
            """.trimIndent(),
            LocalDateTime.now(),
            actorUserId,
            workspaceId,
            snapshotId
        )
    }

    fun insertNode(
        workspaceId: String,
        snapshotId: String,
        parentId: String?,
        label: String,
        depth: Int,
        locked: Boolean
    ): TreeNodeRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO tree_node(id, workspace_id, snapshot_id, parent_id, label, depth, locked, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            workspaceId,
            snapshotId,
            parentId,
            label,
            depth,
            locked,
            now
        )
        return TreeNodeRow(id, workspaceId, snapshotId, parentId, label, depth, locked, now)
    }

    fun listNodes(workspaceId: String, snapshotId: String): List<TreeNodeRow> = jdbcTemplate.query(
        "SELECT * FROM tree_node WHERE workspace_id = ? AND snapshot_id = ? ORDER BY depth, label",
        nodeMapper,
        workspaceId,
        snapshotId
    )

    fun findNodeByWorkspace(workspaceId: String, nodeId: String): TreeNodeRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM tree_node WHERE workspace_id = ? AND id = ?",
        nodeMapper,
        workspaceId,
        nodeId
    )

    fun updateNodeLock(workspaceId: String, nodeId: String, locked: Boolean) {
        jdbcTemplate.update(
            "UPDATE tree_node SET locked = ? WHERE workspace_id = ? AND id = ?",
            locked,
            workspaceId,
            nodeId
        )
    }

    fun renameNode(workspaceId: String, nodeId: String, label: String) {
        jdbcTemplate.update(
            "UPDATE tree_node SET label = ? WHERE workspace_id = ? AND id = ?",
            label,
            workspaceId,
            nodeId
        )
    }

    fun insertMembership(
        workspaceId: String,
        snapshotId: String,
        nodeId: String,
        documentId: String,
        rationaleJson: String
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO tree_membership(id, workspace_id, snapshot_id, node_id, document_id, rationale_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID().toString(),
            workspaceId,
            snapshotId,
            nodeId,
            documentId,
            rationaleJson,
            LocalDateTime.now()
        )
    }

    fun listMemberships(workspaceId: String, snapshotId: String): List<TreeMembershipRow> = jdbcTemplate.query(
        "SELECT * FROM tree_membership WHERE workspace_id = ? AND snapshot_id = ?",
        membershipMapper,
        workspaceId,
        snapshotId
    )

    fun findMembershipByDocInSnapshot(workspaceId: String, snapshotId: String, documentId: String): TreeMembershipRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM tree_membership WHERE workspace_id = ? AND snapshot_id = ? AND document_id = ?",
        membershipMapper,
        workspaceId,
        snapshotId,
        documentId
    )

    fun moveDocumentInActiveSnapshot(workspaceId: String, documentId: String, toNodeId: String) {
        val active = findActiveSnapshot(workspaceId) ?: return
        jdbcTemplate.update(
            "UPDATE tree_membership SET node_id = ? WHERE workspace_id = ? AND snapshot_id = ? AND document_id = ?",
            toNodeId,
            workspaceId,
            active.id,
            documentId
        )
    }

    fun findMembershipByWorkspaceAndDocument(workspaceId: String, documentId: String): TreeMembershipRow? {
        val active = findActiveSnapshot(workspaceId) ?: return null
        return findMembershipByDocInSnapshot(workspaceId, active.id, documentId)
    }
}

@Repository
class FeedbackRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<FeedbackEventRow> { rs: ResultSet, _: Int ->
        FeedbackEventRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            userId = rs.getString("user_id"),
            eventType = rs.getString("event_type"),
            payloadJson = rs.getString("payload_json"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun insert(workspaceId: String, userId: String, eventType: String, payloadJson: String) {
        jdbcTemplate.update(
            """
            INSERT INTO feedback_event(id, workspace_id, user_id, event_type, payload_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID().toString(),
            workspaceId,
            userId,
            eventType,
            payloadJson,
            LocalDateTime.now()
        )
    }

    fun listByWorkspace(workspaceId: String, limit: Int): List<FeedbackEventRow> = jdbcTemplate.query(
        "SELECT * FROM feedback_event WHERE workspace_id = ? ORDER BY created_at DESC LIMIT ?",
        mapper,
        workspaceId,
        limit
    )
}

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
        val args = mutableListOf<Any>(workspaceId)
        val sql = StringBuilder("SELECT * FROM audit_log WHERE workspace_id = ?")
        if (!type.isNullOrBlank()) {
            sql.append(" AND action = ?")
            args.add(type)
        }
        sql.append(" ORDER BY created_at DESC LIMIT 300")
        return jdbcTemplate.query(sql.toString(), mapper, *args.toTypedArray())
    }
}
