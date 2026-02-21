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

private fun ResultSet.getNullableDouble(column: String): Double? {
    val raw = getObject(column) as? Number ?: return null
    return raw.toDouble()
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
    val updatedBy: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val templateScore: Double? = null,
    val templateBoilerplateRatio: Double? = null,
    val templateNgramRepeatRatio: Double? = null,
    val templateDetectedAt: LocalDateTime? = null,
    val parentDocumentId: String? = null
)

data class DocumentFavoriteRow(
    val workspaceId: String,
    val userId: String,
    val documentId: String,
    val createdAt: LocalDateTime
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
    val inputHash: String,
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
    val viewType: String,
    val status: String,
    val movedRatio: Double,
    val churnCount: Int,
    val nodeRenameCount: Int,
    val labelCacheJson: String,
    val createdAt: LocalDateTime,
    val activatedAt: LocalDateTime?,
    val activatedBy: String?
)

data class TreeNodeRow(
    val id: String,
    val workspaceId: String,
    val snapshotId: String,
    val viewType: String,
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
    val viewType: String,
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

data class UserRuleRow(
    val id: String,
    val workspaceId: String,
    val ruleType: String,
    val ruleValue: String,
    val ruleEffect: String,
    val nodeId: String,
    val enabled: Boolean,
    val createdBy: String,
    val createdAt: LocalDateTime
)

data class WorkspaceTreePolicyRow(
    val workspaceId: String,
    val autoThreshold: Double,
    val recommendThreshold: Double,
    val quarantineEnabled: Boolean,
    val rerankerEnabled: Boolean,
    val updatedBy: String,
    val updatedAt: LocalDateTime
)

data class WorkspaceQuestionControlRow(
    val workspaceId: String,
    val enabled: Boolean,
    val updatedBy: String,
    val updatedAt: LocalDateTime
)

data class ActiveLearningQuestionRow(
    val id: String,
    val workspaceId: String,
    val snapshotId: String?,
    val questionType: String,
    val status: String,
    val documentId: String,
    val payloadJson: String,
    val impactScore: Double,
    val answerValue: String?,
    val answeredBy: String?,
    val answeredAt: LocalDateTime?,
    val expiresAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class ConceptPrototypeRow(
    val id: String,
    val workspaceId: String,
    val snapshotId: String,
    val conceptKey: String,
    val label: String,
    val prototypeVectorJson: String,
    val exemplarDocIdsJson: String,
    val docCount: Int,
    val driftScore: Double,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
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
            parentDocumentId = rs.getString("parent_document_id"),
            sourceType = rs.getString("source_type"),
            status = rs.getString("status"),
            version = rs.getLong("version"),
            deleted = rs.getBoolean("deleted"),
            createdBy = rs.getString("created_by"),
            updatedBy = rs.getString("updated_by"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime(),
            templateScore = rs.getNullableDouble("template_score"),
            templateBoilerplateRatio = rs.getNullableDouble("template_boilerplate_ratio"),
            templateNgramRepeatRatio = rs.getNullableDouble("template_ngram_repeat_ratio"),
            templateDetectedAt = rs.getTimestamp("template_detected_at")?.toLocalDateTime()
        )
    }

    fun create(
        workspaceId: String,
        title: String,
        bodyMarkdown: String?,
        bodyText: String?,
        sourceType: String,
        createdBy: String,
        parentDocumentId: String? = null
    ): DocumentRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO documents(
                id, workspace_id, title, body_markdown, body_text, parent_document_id, source_type,
                status, version, deleted, created_by, updated_by, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, false, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            workspaceId,
            title,
            bodyMarkdown,
            bodyText,
            parentDocumentId,
            sourceType,
            "PROCESSING",
            createdBy,
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
            parentDocumentId = parentDocumentId,
            sourceType = sourceType,
            status = "PROCESSING",
            version = 0,
            deleted = false,
            createdBy = createdBy,
            updatedBy = createdBy,
            createdAt = now,
            updatedAt = now,
            templateScore = null,
            templateBoilerplateRatio = null,
            templateNgramRepeatRatio = null,
            templateDetectedAt = null
        )
    }

    fun findByWorkspaceAndId(workspaceId: String, documentId: String): DocumentRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM documents WHERE workspace_id = ? AND id = ? AND deleted = false",
        mapper,
        workspaceId,
        documentId
    )

    fun findDeletedByWorkspaceAndId(workspaceId: String, documentId: String): DocumentRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM documents WHERE workspace_id = ? AND id = ? AND deleted = true",
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
        bodyMarkdown: String?,
        updatedBy: String
    ) {
        val updated = jdbcTemplate.update(
            """
            UPDATE documents
            SET title = ?, body_markdown = ?, body_text = ?, version = version + 1, updated_at = ?, updated_by = ?
            WHERE workspace_id = ? AND id = ? AND version = ? AND deleted = false
            """.trimIndent(),
            title,
            bodyMarkdown,
            bodyMarkdown,
            LocalDateTime.now(),
            updatedBy,
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
            """
            UPDATE documents
            SET parent_document_id = NULL, updated_at = ?
            WHERE workspace_id = ? AND parent_document_id = ? AND deleted = false
            """.trimIndent(),
            LocalDateTime.now(),
            workspaceId,
            documentId
        )
        jdbcTemplate.update(
            "UPDATE documents SET deleted = true, status = ?, parent_document_id = NULL, updated_at = ? WHERE workspace_id = ? AND id = ?",
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

    fun updateTemplateSignals(
        workspaceId: String,
        documentId: String,
        templateScore: Double,
        templateBoilerplateRatio: Double,
        templateNgramRepeatRatio: Double,
        templateDetectedAt: LocalDateTime?
    ) {
        jdbcTemplate.update(
            """
            UPDATE documents
            SET template_score = ?,
                template_boilerplate_ratio = ?,
                template_ngram_repeat_ratio = ?,
                template_detected_at = ?
            WHERE workspace_id = ? AND id = ? AND deleted = false
            """.trimIndent(),
            templateScore,
            templateBoilerplateRatio,
            templateNgramRepeatRatio,
            templateDetectedAt,
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

    fun listDeletedByWorkspace(
        workspaceId: String,
        query: String?,
        page: Int,
        size: Int
    ): List<DocumentRow> {
        val sql = StringBuilder("SELECT * FROM documents WHERE workspace_id = ? AND deleted = true")
        val args = mutableListOf<Any>(workspaceId)
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

    fun countDeletedByWorkspace(workspaceId: String, query: String?): Long {
        val sql = StringBuilder("SELECT COUNT(*) FROM documents WHERE workspace_id = ? AND deleted = true")
        val args = mutableListOf<Any>(workspaceId)
        if (!query.isNullOrBlank()) {
            sql.append(" AND (LOWER(title) LIKE ? OR LOWER(COALESCE(body_text, '')) LIKE ?)")
            val pattern = "%${query.lowercase()}%"
            args.add(pattern)
            args.add(pattern)
        }
        return jdbcTemplate.queryForObject(sql.toString(), Long::class.java, *args.toTypedArray()) ?: 0
    }

    fun restore(workspaceId: String, documentId: String, status: String = "PROCESSING") {
        jdbcTemplate.update(
            """
            UPDATE documents
            SET deleted = false, status = ?, parent_document_id = NULL, updated_at = ?
            WHERE workspace_id = ? AND id = ? AND deleted = true
            """.trimIndent(),
            status,
            LocalDateTime.now(),
            workspaceId,
            documentId
        )
    }

    fun moveParent(workspaceId: String, documentId: String, parentDocumentId: String?) {
        jdbcTemplate.update(
            """
            UPDATE documents
            SET parent_document_id = ?, updated_at = ?
            WHERE workspace_id = ? AND id = ? AND deleted = false
            """.trimIndent(),
            parentDocumentId,
            LocalDateTime.now(),
            workspaceId,
            documentId
        )
    }
}

@Repository
class DocumentFavoriteRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<DocumentFavoriteRow> { rs: ResultSet, _: Int ->
        DocumentFavoriteRow(
            workspaceId = rs.getString("workspace_id"),
            userId = rs.getString("user_id"),
            documentId = rs.getString("document_id"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun listByWorkspaceAndUser(workspaceId: String, userId: String): List<DocumentFavoriteRow> = jdbcTemplate.query(
        """
        SELECT f.workspace_id, f.user_id, f.document_id, f.created_at
        FROM document_favorite f
        JOIN documents d ON d.id = f.document_id
        WHERE f.workspace_id = ?
          AND f.user_id = ?
          AND d.workspace_id = f.workspace_id
          AND d.deleted = false
        ORDER BY f.created_at DESC
        """.trimIndent(),
        mapper,
        workspaceId,
        userId
    )

    fun add(workspaceId: String, userId: String, documentId: String) {
        try {
            jdbcTemplate.update(
                """
                INSERT INTO document_favorite(workspace_id, user_id, document_id, created_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                workspaceId,
                userId,
                documentId,
                LocalDateTime.now()
            )
        } catch (_: DuplicateKeyException) {
            // idempotent add
        }
    }

    fun remove(workspaceId: String, userId: String, documentId: String) {
        jdbcTemplate.update(
            """
            DELETE FROM document_favorite
            WHERE workspace_id = ? AND user_id = ? AND document_id = ?
            """.trimIndent(),
            workspaceId,
            userId,
            documentId
        )
    }

    fun removeByDocument(workspaceId: String, documentId: String) {
        jdbcTemplate.update(
            """
            DELETE FROM document_favorite
            WHERE workspace_id = ? AND document_id = ?
            """.trimIndent(),
            workspaceId,
            documentId
        )
    }
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

    fun markRetryPendingFromStage(workspaceId: String, documentId: String, stage: Stage) {
        val now = LocalDateTime.now()
        when (stage) {
            Stage.INGEST -> jdbcTemplate.update(
                """
                UPDATE pipeline_status
                SET ingest_status = ?, embed_status = ?, index_status = ?, tree_status = ?, failure_reason = NULL, updated_at = ?
                WHERE workspace_id = ? AND document_id = ?
                """.trimIndent(),
                StageStatus.PENDING.name,
                StageStatus.PENDING.name,
                StageStatus.PENDING.name,
                StageStatus.PENDING.name,
                now,
                workspaceId,
                documentId
            )

            Stage.EMBED -> jdbcTemplate.update(
                """
                UPDATE pipeline_status
                SET embed_status = ?, index_status = ?, tree_status = ?, failure_reason = NULL, updated_at = ?
                WHERE workspace_id = ? AND document_id = ?
                """.trimIndent(),
                StageStatus.PENDING.name,
                StageStatus.PENDING.name,
                StageStatus.PENDING.name,
                now,
                workspaceId,
                documentId
            )

            Stage.INDEX -> jdbcTemplate.update(
                """
                UPDATE pipeline_status
                SET index_status = ?, tree_status = ?, failure_reason = NULL, updated_at = ?
                WHERE workspace_id = ? AND document_id = ?
                """.trimIndent(),
                StageStatus.PENDING.name,
                StageStatus.PENDING.name,
                now,
                workspaceId,
                documentId
            )

            Stage.TREE -> jdbcTemplate.update(
                """
                UPDATE pipeline_status
                SET tree_status = ?, failure_reason = NULL, updated_at = ?
                WHERE workspace_id = ? AND document_id = ?
                """.trimIndent(),
                StageStatus.PENDING.name,
                now,
                workspaceId,
                documentId
            )
        }
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

    fun listByWorkspace(workspaceId: String): List<AttachmentRow> = jdbcTemplate.query(
        "SELECT * FROM attachments WHERE workspace_id = ? ORDER BY created_at",
        mapper,
        workspaceId
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
            inputHash = rs.getString("input_hash"),
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
        inputHash: String,
        vectorJson: String,
        modelVersion: String
    ) {
        val now = LocalDateTime.now()
        try {
            jdbcTemplate.update(
                """
                INSERT INTO embeddings(
                    id, workspace_id, document_id, target_type, target_id, input_hash, vector_json, model_version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID().toString(),
                workspaceId,
                documentId,
                targetType,
                targetId,
                inputHash,
                vectorJson,
                modelVersion,
                now
            )
        } catch (_: DuplicateKeyException) {
            jdbcTemplate.update(
                """
                UPDATE embeddings
                SET vector_json = ?, created_at = ?
                WHERE workspace_id = ? AND target_type = ? AND target_id = ? AND model_version = ? AND input_hash = ?
                """.trimIndent(),
                vectorJson,
                now,
                workspaceId,
                targetType,
                targetId,
                modelVersion,
                inputHash
            )
        }
    }

    fun findByInputHash(
        workspaceId: String,
        targetType: String,
        targetId: String,
        modelVersion: String,
        inputHash: String
    ): EmbeddingRow? = jdbcTemplate.queryOneOrNull(
        """
        SELECT * FROM embeddings
        WHERE workspace_id = ? AND target_type = ? AND target_id = ? AND model_version = ? AND input_hash = ?
        ORDER BY created_at DESC
        LIMIT 1
        """.trimIndent(),
        mapper,
        workspaceId,
        targetType,
        targetId,
        modelVersion,
        inputHash
    )

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

    fun listByWorkspaceAndModel(workspaceId: String, modelVersion: String): List<EmbeddingRow> = jdbcTemplate.query(
        """
        SELECT id, workspace_id, document_id, target_type, target_id, input_hash, vector_json, model_version, created_at
        FROM (
            SELECT *,
                   ROW_NUMBER() OVER (
                       PARTITION BY workspace_id, document_id, target_type, target_id, model_version
                       ORDER BY created_at DESC
                   ) AS rn
            FROM embeddings
            WHERE workspace_id = ? AND model_version = ?
        ) latest
        WHERE latest.rn = 1
        ORDER BY document_id, target_type
        """.trimIndent(),
        mapper,
        workspaceId,
        modelVersion
    )

    fun listByWorkspaceAndDocumentAndModel(
        workspaceId: String,
        documentId: String,
        modelVersion: String
    ): List<EmbeddingRow> = jdbcTemplate.query(
        """
        SELECT id, workspace_id, document_id, target_type, target_id, input_hash, vector_json, model_version, created_at
        FROM (
            SELECT *,
                   ROW_NUMBER() OVER (
                       PARTITION BY workspace_id, document_id, target_type, target_id, model_version
                       ORDER BY created_at DESC
                   ) AS rn
            FROM embeddings
            WHERE workspace_id = ? AND document_id = ? AND model_version = ?
        ) latest
        WHERE latest.rn = 1
        ORDER BY target_type, target_id
        """.trimIndent(),
        mapper,
        workspaceId,
        documentId,
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

    fun tryStart(
        workspaceId: String,
        documentId: String,
        stage: Stage,
        inputHash: String,
        modelVersion: String,
        allowReopenDone: Boolean = false
    ): Boolean {
        val now = LocalDateTime.now()
        val staleRunningCutoff = now.minusMinutes(10)
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
            val reopened = if (allowReopenDone) {
                jdbcTemplate.update(
                    """
                    UPDATE stage_execution
                    SET status = ?, message = NULL, updated_at = ?
                    WHERE workspace_id = ? AND document_id = ? AND stage = ? AND input_hash = ? AND model_version = ?
                      AND (status IN (?, ?) OR (status = ? AND updated_at < ?))
                    """.trimIndent(),
                    StageStatus.RUNNING.name,
                    now,
                    workspaceId,
                    documentId,
                    stage.name,
                    inputHash,
                    modelVersion,
                    StageStatus.FAILED.name,
                    StageStatus.DONE.name,
                    StageStatus.RUNNING.name,
                    staleRunningCutoff
                )
            } else {
                jdbcTemplate.update(
                    """
                    UPDATE stage_execution
                    SET status = ?, message = NULL, updated_at = ?
                    WHERE workspace_id = ? AND document_id = ? AND stage = ? AND input_hash = ? AND model_version = ?
                      AND (status = ? OR (status = ? AND updated_at < ?))
                    """.trimIndent(),
                    StageStatus.RUNNING.name,
                    now,
                    workspaceId,
                    documentId,
                    stage.name,
                    inputHash,
                    modelVersion,
                    StageStatus.FAILED.name,
                    StageStatus.RUNNING.name,
                    staleRunningCutoff
                )
            }
            reopened > 0
        }
    }

    fun findByKey(workspaceId: String, documentId: String, stage: Stage, inputHash: String, modelVersion: String): StageExecutionRow? =
        jdbcTemplate.queryOneOrNull(
            """
            SELECT * FROM stage_execution
            WHERE workspace_id = ? AND document_id = ? AND stage = ? AND input_hash = ? AND model_version = ?
            LIMIT 1
            """.trimIndent(),
            mapper,
            workspaceId,
            documentId,
            stage.name,
            inputHash,
            modelVersion
        )

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
            viewType = rs.getString("view_type"),
            status = rs.getString("status"),
            movedRatio = rs.getDouble("moved_ratio"),
            churnCount = rs.getInt("churn_count"),
            nodeRenameCount = rs.getInt("node_rename_count"),
            labelCacheJson = rs.getString("label_cache_json"),
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
            viewType = rs.getString("view_type"),
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
            viewType = rs.getString("view_type"),
            nodeId = rs.getString("node_id"),
            documentId = rs.getString("document_id"),
            rationaleJson = rs.getString("rationale_json"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun createSnapshot(
        workspaceId: String,
        viewType: String = "TOPIC",
        status: String,
        movedRatio: Double,
        churnCount: Int,
        nodeRenameCount: Int,
        labelCacheJson: String = "{}"
    ): TreeSnapshotRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO tree_snapshot(
                id, workspace_id, view_type, status, moved_ratio, churn_count, node_rename_count, label_cache_json, created_at, activated_at, activated_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)
            """.trimIndent(),
            id,
            workspaceId,
            viewType,
            status,
            movedRatio,
            churnCount,
            nodeRenameCount,
            labelCacheJson,
            now
        )
        return TreeSnapshotRow(id, workspaceId, viewType, status, movedRatio, churnCount, nodeRenameCount, labelCacheJson, now, null, null)
    }

    fun findActiveSnapshot(workspaceId: String, viewType: String = "TOPIC"): TreeSnapshotRow? = jdbcTemplate.queryOneOrNull(
        """
        SELECT *
        FROM tree_snapshot
        WHERE workspace_id = ?
          AND view_type = ?
          AND status = 'ACTIVE'
        ORDER BY created_at DESC
        LIMIT 1
        """.trimIndent(),
        snapshotMapper,
        workspaceId,
        viewType
    )

    fun listLockedNodesInActiveSnapshot(workspaceId: String, viewType: String = "TOPIC"): List<TreeNodeRow> {
        val active = findActiveSnapshot(workspaceId, viewType) ?: return emptyList()
        return jdbcTemplate.query(
            "SELECT * FROM tree_node WHERE workspace_id = ? AND snapshot_id = ? AND view_type = ? AND locked = true",
            nodeMapper,
            workspaceId,
            active.id,
            viewType
        )
    }

    fun findSnapshotByWorkspace(workspaceId: String, snapshotId: String): TreeSnapshotRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM tree_snapshot WHERE workspace_id = ? AND id = ?",
        snapshotMapper,
        workspaceId,
        snapshotId
    )

    fun listSnapshots(workspaceId: String, viewType: String = "TOPIC"): List<TreeSnapshotRow> = jdbcTemplate.query(
        "SELECT * FROM tree_snapshot WHERE workspace_id = ? AND view_type = ? ORDER BY created_at DESC LIMIT 30",
        snapshotMapper,
        workspaceId,
        viewType
    )

    fun markAllSnapshotsRecommended(workspaceId: String, viewType: String = "TOPIC") {
        jdbcTemplate.update(
            "UPDATE tree_snapshot SET status = 'RECOMMENDED' WHERE workspace_id = ? AND view_type = ? AND status = 'ACTIVE'",
            workspaceId,
            viewType
        )
    }

    fun activateSnapshot(workspaceId: String, snapshotId: String, actorUserId: String, viewType: String = "TOPIC") {
        jdbcTemplate.update(
            "UPDATE tree_snapshot SET status = 'RECOMMENDED' WHERE workspace_id = ? AND view_type = ? AND status = 'ACTIVE'",
            workspaceId,
            viewType
        )
        jdbcTemplate.update(
            """
            UPDATE tree_snapshot
            SET status = 'ACTIVE', activated_at = ?, activated_by = ?
            WHERE workspace_id = ? AND id = ? AND view_type = ?
            """.trimIndent(),
            LocalDateTime.now(),
            actorUserId,
            workspaceId,
            snapshotId,
            viewType
        )
    }

    fun insertNode(
        workspaceId: String,
        snapshotId: String,
        viewType: String = "TOPIC",
        parentId: String?,
        label: String,
        depth: Int,
        locked: Boolean
    ): TreeNodeRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO tree_node(id, workspace_id, snapshot_id, view_type, parent_id, label, depth, locked, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            workspaceId,
            snapshotId,
            viewType,
            parentId,
            label,
            depth,
            locked,
            now
        )
        return TreeNodeRow(id, workspaceId, snapshotId, viewType, parentId, label, depth, locked, now)
    }

    fun listNodes(workspaceId: String, snapshotId: String, viewType: String = "TOPIC"): List<TreeNodeRow> = jdbcTemplate.query(
        "SELECT * FROM tree_node WHERE workspace_id = ? AND snapshot_id = ? AND view_type = ? ORDER BY depth, label",
        nodeMapper,
        workspaceId,
        snapshotId,
        viewType
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
        viewType: String = "TOPIC",
        nodeId: String,
        documentId: String,
        rationaleJson: String
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO tree_membership(id, workspace_id, snapshot_id, view_type, node_id, document_id, rationale_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID().toString(),
            workspaceId,
            snapshotId,
            viewType,
            nodeId,
            documentId,
            rationaleJson,
            LocalDateTime.now()
        )
    }

    fun listMemberships(workspaceId: String, snapshotId: String, viewType: String = "TOPIC"): List<TreeMembershipRow> = jdbcTemplate.query(
        "SELECT * FROM tree_membership WHERE workspace_id = ? AND snapshot_id = ? AND view_type = ?",
        membershipMapper,
        workspaceId,
        snapshotId,
        viewType
    )

    fun findMembershipByDocInSnapshot(workspaceId: String, snapshotId: String, documentId: String, viewType: String = "TOPIC"): TreeMembershipRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM tree_membership WHERE workspace_id = ? AND snapshot_id = ? AND document_id = ? AND view_type = ?",
        membershipMapper,
        workspaceId,
        snapshotId,
        documentId,
        viewType
    )

    fun moveDocumentInActiveSnapshot(workspaceId: String, documentId: String, toNodeId: String, viewType: String = "TOPIC") {
        val active = findActiveSnapshot(workspaceId, viewType) ?: return
        jdbcTemplate.update(
            "UPDATE tree_membership SET node_id = ? WHERE workspace_id = ? AND snapshot_id = ? AND document_id = ? AND view_type = ?",
            toNodeId,
            workspaceId,
            active.id,
            documentId,
            viewType
        )
    }

    fun findMembershipByWorkspaceAndDocument(workspaceId: String, documentId: String, viewType: String = "TOPIC"): TreeMembershipRow? {
        val active = findActiveSnapshot(workspaceId, viewType) ?: return null
        return findMembershipByDocInSnapshot(workspaceId, active.id, documentId, viewType)
    }
}

@Repository
class ConceptPrototypeRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<ConceptPrototypeRow> { rs: ResultSet, _: Int ->
        ConceptPrototypeRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            snapshotId = rs.getString("snapshot_id"),
            conceptKey = rs.getString("concept_key"),
            label = rs.getString("label"),
            prototypeVectorJson = rs.getString("prototype_vector_json"),
            exemplarDocIdsJson = rs.getString("exemplar_doc_ids_json"),
            docCount = rs.getInt("doc_count"),
            driftScore = rs.getDouble("drift_score"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    fun listByWorkspaceAndSnapshot(workspaceId: String, snapshotId: String): List<ConceptPrototypeRow> = jdbcTemplate.query(
        """
        SELECT *
        FROM concept_prototype
        WHERE workspace_id = ? AND snapshot_id = ?
        ORDER BY doc_count DESC, label
        """.trimIndent(),
        mapper,
        workspaceId,
        snapshotId
    )

    fun listByWorkspaceAndActiveSnapshot(workspaceId: String, viewType: String = "TOPIC"): List<ConceptPrototypeRow> = jdbcTemplate.query(
        """
        SELECT cp.*
        FROM concept_prototype cp
        JOIN tree_snapshot ts ON ts.id = cp.snapshot_id AND ts.workspace_id = cp.workspace_id
        WHERE cp.workspace_id = ?
          AND ts.status = 'ACTIVE'
          AND ts.view_type = ?
        ORDER BY cp.doc_count DESC, cp.label
        """.trimIndent(),
        mapper,
        workspaceId,
        viewType
    )

    fun replaceSnapshotConcepts(workspaceId: String, snapshotId: String, concepts: List<ConceptPrototypeRow>) {
        jdbcTemplate.update(
            "DELETE FROM concept_prototype WHERE workspace_id = ? AND snapshot_id = ?",
            workspaceId,
            snapshotId
        )
        concepts.forEach { row ->
            jdbcTemplate.update(
                """
                INSERT INTO concept_prototype(
                    id,
                    workspace_id,
                    snapshot_id,
                    concept_key,
                    label,
                    prototype_vector_json,
                    exemplar_doc_ids_json,
                    doc_count,
                    drift_score,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                row.id,
                workspaceId,
                snapshotId,
                row.conceptKey,
                row.label,
                row.prototypeVectorJson,
                row.exemplarDocIdsJson,
                row.docCount,
                row.driftScore,
                row.createdAt,
                row.updatedAt
            )
        }
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
class WorkspaceTreePolicyRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<WorkspaceTreePolicyRow> { rs: ResultSet, _: Int ->
        WorkspaceTreePolicyRow(
            workspaceId = rs.getString("workspace_id"),
            autoThreshold = rs.getDouble("auto_threshold"),
            recommendThreshold = rs.getDouble("recommend_threshold"),
            quarantineEnabled = rs.getBoolean("quarantine_enabled"),
            rerankerEnabled = rs.getBoolean("reranker_enabled"),
            updatedBy = rs.getString("updated_by"),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    fun findByWorkspace(workspaceId: String): WorkspaceTreePolicyRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM workspace_tree_policy WHERE workspace_id = ?",
        mapper,
        workspaceId
    )

    fun upsert(
        workspaceId: String,
        autoThreshold: Double,
        recommendThreshold: Double,
        quarantineEnabled: Boolean,
        rerankerEnabled: Boolean,
        updatedBy: String
    ): WorkspaceTreePolicyRow {
        val now = LocalDateTime.now()
        val updated = jdbcTemplate.update(
            """
            UPDATE workspace_tree_policy
            SET auto_threshold = ?,
                recommend_threshold = ?,
                quarantine_enabled = ?,
                reranker_enabled = ?,
                updated_by = ?,
                updated_at = ?
            WHERE workspace_id = ?
            """.trimIndent(),
            autoThreshold,
            recommendThreshold,
            quarantineEnabled,
            rerankerEnabled,
            updatedBy,
            now,
            workspaceId
        )
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO workspace_tree_policy(
                        workspace_id, auto_threshold, recommend_threshold, quarantine_enabled, reranker_enabled, updated_by, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    workspaceId,
                    autoThreshold,
                    recommendThreshold,
                    quarantineEnabled,
                    rerankerEnabled,
                    updatedBy,
                    now
                )
            } catch (_: DuplicateKeyException) {
                jdbcTemplate.update(
                    """
                    UPDATE workspace_tree_policy
                    SET auto_threshold = ?,
                        recommend_threshold = ?,
                        quarantine_enabled = ?,
                        reranker_enabled = ?,
                        updated_by = ?,
                        updated_at = ?
                    WHERE workspace_id = ?
                    """.trimIndent(),
                    autoThreshold,
                    recommendThreshold,
                    quarantineEnabled,
                    rerankerEnabled,
                    updatedBy,
                    now,
                    workspaceId
                )
            }
        }
        return findByWorkspace(workspaceId) ?: WorkspaceTreePolicyRow(
            workspaceId,
            autoThreshold = autoThreshold,
            recommendThreshold = recommendThreshold,
            quarantineEnabled = quarantineEnabled,
            rerankerEnabled = rerankerEnabled,
            updatedBy = updatedBy,
            updatedAt = now
        )
    }
}

@Repository
class WorkspaceQuestionControlRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<WorkspaceQuestionControlRow> { rs: ResultSet, _: Int ->
        WorkspaceQuestionControlRow(
            workspaceId = rs.getString("workspace_id"),
            enabled = rs.getBoolean("enabled"),
            updatedBy = rs.getString("updated_by"),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    fun findByWorkspace(workspaceId: String): WorkspaceQuestionControlRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM workspace_question_control WHERE workspace_id = ?",
        mapper,
        workspaceId
    )

    fun upsert(workspaceId: String, enabled: Boolean, updatedBy: String): WorkspaceQuestionControlRow {
        val now = LocalDateTime.now()
        val existing = findByWorkspace(workspaceId)
        if (existing == null) {
            jdbcTemplate.update(
                """
                INSERT INTO workspace_question_control(workspace_id, enabled, updated_by, updated_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                workspaceId,
                enabled,
                updatedBy,
                now
            )
        } else {
            jdbcTemplate.update(
                """
                UPDATE workspace_question_control
                SET enabled = ?, updated_by = ?, updated_at = ?
                WHERE workspace_id = ?
                """.trimIndent(),
                enabled,
                updatedBy,
                now,
                workspaceId
            )
        }
        return findByWorkspace(workspaceId) ?: WorkspaceQuestionControlRow(
            workspaceId = workspaceId,
            enabled = enabled,
            updatedBy = updatedBy,
            updatedAt = now
        )
    }
}

@Repository
class ActiveLearningQuestionRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<ActiveLearningQuestionRow> { rs: ResultSet, _: Int ->
        ActiveLearningQuestionRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            snapshotId = rs.getString("snapshot_id"),
            questionType = rs.getString("question_type"),
            status = rs.getString("status"),
            documentId = rs.getString("document_id"),
            payloadJson = rs.getString("payload_json"),
            impactScore = rs.getDouble("impact_score"),
            answerValue = rs.getString("answer_value"),
            answeredBy = rs.getString("answered_by"),
            answeredAt = rs.getTimestamp("answered_at")?.toLocalDateTime(),
            expiresAt = rs.getTimestamp("expires_at")?.toLocalDateTime(),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    fun create(
        workspaceId: String,
        snapshotId: String?,
        questionType: String,
        documentId: String,
        payloadJson: String,
        impactScore: Double,
        expiresAt: LocalDateTime?
    ): ActiveLearningQuestionRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO active_learning_question(
                id, workspace_id, snapshot_id, question_type, status, document_id, payload_json,
                impact_score, answer_value, answered_by, answered_at, expires_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?)
            """.trimIndent(),
            id,
            workspaceId,
            snapshotId,
            questionType,
            "OPEN",
            documentId,
            payloadJson,
            impactScore,
            expiresAt,
            now,
            now
        )
        return findByWorkspaceAndId(workspaceId, id)
            ?: error("failed to create active learning question")
    }

    fun findByWorkspaceAndId(workspaceId: String, questionId: String): ActiveLearningQuestionRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM active_learning_question WHERE workspace_id = ? AND id = ?",
        mapper,
        workspaceId,
        questionId
    )

    fun listByWorkspace(workspaceId: String, status: String?, limit: Int): List<ActiveLearningQuestionRow> {
        val args = mutableListOf<Any>(workspaceId)
        val sql = StringBuilder("SELECT * FROM active_learning_question WHERE workspace_id = ?")
        if (!status.isNullOrBlank()) {
            sql.append(" AND status = ?")
            args += status
        }
        sql.append(" ORDER BY impact_score DESC, created_at DESC LIMIT ?")
        args += limit.coerceIn(1, 200)
        return jdbcTemplate.query(sql.toString(), mapper, *args.toTypedArray())
    }

    fun listOpenByWorkspace(workspaceId: String, limit: Int): List<ActiveLearningQuestionRow> = jdbcTemplate.query(
        """
        SELECT * FROM active_learning_question
        WHERE workspace_id = ? AND status = 'OPEN'
        ORDER BY impact_score DESC, created_at DESC
        LIMIT ?
        """.trimIndent(),
        mapper,
        workspaceId,
        limit.coerceIn(1, 400)
    )

    fun countByWorkspaceAndStatus(workspaceId: String, status: String): Long {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM active_learning_question WHERE workspace_id = ? AND status = ?",
            Long::class.java,
            workspaceId,
            status
        ) ?: 0L
    }

    fun averageImpactByWorkspaceAndStatus(workspaceId: String, status: String): Double {
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(AVG(impact_score), 0) FROM active_learning_question WHERE workspace_id = ? AND status = ?",
            Double::class.java,
            workspaceId,
            status
        ) ?: 0.0
    }

    fun markAnswered(workspaceId: String, questionId: String, answerValue: String, answeredBy: String): Int {
        val now = LocalDateTime.now()
        return jdbcTemplate.update(
            """
            UPDATE active_learning_question
            SET status = 'ANSWERED', answer_value = ?, answered_by = ?, answered_at = ?, updated_at = ?
            WHERE workspace_id = ? AND id = ? AND status = 'OPEN'
            """.trimIndent(),
            answerValue,
            answeredBy,
            now,
            now,
            workspaceId,
            questionId
        )
    }

    fun expireStale(workspaceId: String, now: LocalDateTime): Int {
        return jdbcTemplate.update(
            """
            UPDATE active_learning_question
            SET status = 'EXPIRED', updated_at = ?
            WHERE workspace_id = ? AND status = 'OPEN' AND expires_at IS NOT NULL AND expires_at < ?
            """.trimIndent(),
            now,
            workspaceId,
            now
        )
    }

    fun expireAllOpen(workspaceId: String): Int {
        val now = LocalDateTime.now()
        return jdbcTemplate.update(
            """
            UPDATE active_learning_question
            SET status = 'EXPIRED', updated_at = ?
            WHERE workspace_id = ? AND status = 'OPEN'
            """.trimIndent(),
            now,
            workspaceId
        )
    }
}

@Repository
class UserRuleRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<UserRuleRow> { rs: ResultSet, _: Int ->
        UserRuleRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            ruleType = rs.getString("rule_type"),
            ruleValue = rs.getString("rule_value"),
            ruleEffect = rs.getString("rule_effect"),
            nodeId = rs.getString("node_id"),
            enabled = rs.getBoolean("enabled"),
            createdBy = rs.getString("created_by"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun create(
        workspaceId: String,
        ruleType: String,
        ruleValue: String,
        ruleEffect: String,
        nodeId: String,
        createdBy: String
    ): UserRuleRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO user_rule(
                id, workspace_id, rule_type, rule_value, rule_effect, node_id, enabled, created_by, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            workspaceId,
            ruleType,
            ruleValue,
            ruleEffect,
            nodeId,
            true,
            createdBy,
            now
        )
        return UserRuleRow(id, workspaceId, ruleType, ruleValue, ruleEffect, nodeId, true, createdBy, now)
    }

    fun listByWorkspace(workspaceId: String): List<UserRuleRow> = jdbcTemplate.query(
        "SELECT * FROM user_rule WHERE workspace_id = ? AND enabled = true ORDER BY created_at DESC",
        mapper,
        workspaceId
    )

    fun findByWorkspaceAndId(workspaceId: String, ruleId: String): UserRuleRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM user_rule WHERE workspace_id = ? AND id = ?",
        mapper,
        workspaceId,
        ruleId
    )

    fun update(
        workspaceId: String,
        ruleId: String,
        ruleType: String,
        ruleValue: String,
        ruleEffect: String,
        nodeId: String
    ): UserRuleRow? {
        jdbcTemplate.update(
            """
            UPDATE user_rule
            SET rule_type = ?, rule_value = ?, rule_effect = ?, node_id = ?
            WHERE workspace_id = ? AND id = ?
            """.trimIndent(),
            ruleType,
            ruleValue,
            ruleEffect,
            nodeId,
            workspaceId,
            ruleId
        )
        return findByWorkspaceAndId(workspaceId, ruleId)
    }

    fun delete(workspaceId: String, ruleId: String) {
        jdbcTemplate.update(
            "DELETE FROM user_rule WHERE workspace_id = ? AND id = ?",
            workspaceId,
            ruleId
        )
    }
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

data class PaletteHistoryRow(
    val id: String,
    val workspaceId: String,
    val userId: String,
    val eventType: String,
    val queryText: String?,
    val documentId: String?,
    val commandKey: String?,
    val createdAt: LocalDateTime
)

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

@Repository
class DocumentAclRepository(private val jdbcTemplate: JdbcTemplate) {
    fun upsert(workspaceId: String, documentId: String, principalUserId: String, permission: String, grantedBy: String) {
        jdbcTemplate.update(
            """
            INSERT INTO document_acl(id, workspace_id, document_id, principal_user_id, permission, granted_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (workspace_id, document_id, principal_user_id)
            DO UPDATE SET permission = EXCLUDED.permission, granted_by = EXCLUDED.granted_by
            """.trimIndent(),
            UUID.randomUUID().toString(),
            workspaceId,
            documentId,
            principalUserId,
            permission,
            grantedBy,
            LocalDateTime.now()
        )
    }

    fun canAccess(workspaceId: String, documentId: String, userId: String): Boolean {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM document_acl
            WHERE workspace_id = ? AND document_id = ? AND principal_user_id = ?
            """.trimIndent(),
            Long::class.java,
            workspaceId,
            documentId,
            userId
        ) ?: 0
        return count > 0
    }
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

data class SearchDocumentRow(
    val id: String,
    val title: String,
    val updatedAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val updatedBy: String,
    val parentDocumentId: String?
)

@Repository
class SearchDocumentRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<SearchDocumentRow> { rs: ResultSet, _: Int ->
        SearchDocumentRow(
            id = rs.getString("id"),
            title = rs.getString("title"),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime(),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            createdBy = rs.getString("created_by"),
            updatedBy = rs.getString("updated_by"),
            parentDocumentId = rs.getString("parent_document_id")
        )
    }

    fun search(
        workspaceId: String,
        userId: String,
        query: String,
        titleOnly: Boolean,
        createdBy: String?,
        updatedBy: String?,
        fromDate: LocalDateTime?,
        toDate: LocalDateTime?,
        scopePageId: String?,
        sortSql: String,
        size: Int,
        offset: Int
    ): List<SearchDocumentRow> {
        val args = mutableListOf<Any>(workspaceId, userId, userId)
        val sql = StringBuilder(
            """
            SELECT d.*
            FROM documents d
            WHERE d.workspace_id = ?
              AND d.deleted = false
              AND (
                EXISTS (SELECT 1 FROM memberships m WHERE m.workspace_id = d.workspace_id AND m.user_id = ?)
                OR EXISTS (
                  SELECT 1 FROM document_acl acl
                  WHERE acl.workspace_id = d.workspace_id
                    AND acl.document_id = d.id
                    AND acl.principal_user_id = ?
                )
              )
            """.trimIndent()
        )

        val pattern = "%${query.lowercase()}%"
        if (titleOnly) {
            sql.append(" AND LOWER(d.title) LIKE ?")
            args.add(pattern)
        } else {
            sql.append(" AND (LOWER(d.title) LIKE ? OR LOWER(COALESCE(d.body_text, '')) LIKE ?)")
            args.add(pattern)
            args.add(pattern)
        }

        if (!createdBy.isNullOrBlank()) {
            sql.append(" AND d.created_by = ?")
            args.add(createdBy)
        }
        if (!updatedBy.isNullOrBlank()) {
            sql.append(" AND d.updated_by = ?")
            args.add(updatedBy)
        }
        if (fromDate != null) {
            sql.append(" AND d.updated_at >= ?")
            args.add(fromDate)
        }
        if (toDate != null) {
            sql.append(" AND d.updated_at <= ?")
            args.add(toDate)
        }
        if (!scopePageId.isNullOrBlank()) {
            sql.append(
                """
                AND d.id IN (
                  WITH RECURSIVE subtree AS (
                    SELECT id FROM documents WHERE workspace_id = ? AND id = ? AND deleted = false
                    UNION ALL
                    SELECT child.id
                    FROM documents child
                    JOIN subtree s ON child.parent_document_id = s.id
                    WHERE child.workspace_id = ? AND child.deleted = false
                  )
                  SELECT id FROM subtree
                )
                """.trimIndent()
            )
            args.add(workspaceId)
            args.add(scopePageId)
            args.add(workspaceId)
        }

        sql.append(" ORDER BY $sortSql LIMIT ? OFFSET ?")
        args.add(size)
        args.add(offset)

        return jdbcTemplate.query(sql.toString(), mapper, *args.toTypedArray())
    }
}
