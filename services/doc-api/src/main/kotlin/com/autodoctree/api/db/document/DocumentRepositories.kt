package com.autodoctree.api.db.document

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
class DocumentRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<DocumentRow> { rs: ResultSet, _: Int ->
        DocumentRow(
            id = rs.getString("id"),
            workspaceId = rs.getString("workspace_id"),
            title = rs.getString("title"),
            bodyMarkdown = rs.getString("body_markdown"),
            bodyText = rs.getString("body_text"),
            blocksJson = rs.getString("blocks_json"),
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
        blocksJson: String? = null,
        sourceType: String,
        createdBy: String,
        parentDocumentId: String? = null
    ): DocumentRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO documents(
                id, workspace_id, title, body_markdown, body_text, blocks_json, parent_document_id, source_type,
                status, version, deleted, created_by, updated_by, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, 0, false, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            workspaceId,
            title,
            bodyMarkdown,
            bodyText,
            blocksJson,
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
            blocksJson = blocksJson,
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
        bodyText: String?,
        blocksJson: String?,
        updatedBy: String
    ) {
        val updated = jdbcTemplate.update(
            """
            UPDATE documents
            SET title = ?, body_markdown = ?, body_text = ?, blocks_json = CAST(? AS JSON), version = version + 1, updated_at = ?, updated_by = ?
            WHERE workspace_id = ? AND id = ? AND version = ? AND deleted = false
            """.trimIndent(),
            title,
            bodyMarkdown,
            bodyText,
            blocksJson,
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

    fun listSubtreeDocumentIds(workspaceId: String, rootDocumentId: String): List<String> {
        val documents = listWorkspaceDocuments(workspaceId)
        if (documents.none { it.id == rootDocumentId }) {
            return emptyList()
        }
        val childrenByParent = mutableMapOf<String, MutableList<String>>()
        documents.forEach { document ->
            val parentId = document.parentDocumentId ?: return@forEach
            childrenByParent.computeIfAbsent(parentId) { mutableListOf() }.add(document.id)
        }
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootDocumentId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) {
                continue
            }
            result += current
            childrenByParent[current].orEmpty().forEach { childId ->
                queue.add(childId)
            }
        }
        return result
    }

    fun softDeleteDocuments(workspaceId: String, documentIds: Collection<String>) {
        if (documentIds.isEmpty()) {
            return
        }
        val placeholders = documentIds.joinToString(",") { "?" }
        val args = mutableListOf<Any>(
            "DELETED",
            LocalDateTime.now(),
            workspaceId
        )
        args.addAll(documentIds)
        jdbcTemplate.update(
            """
            UPDATE documents
            SET deleted = true, status = ?, parent_document_id = NULL, updated_at = ?
            WHERE workspace_id = ? AND id IN ($placeholders) AND deleted = false
            """.trimIndent(),
            *args.toTypedArray()
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

    fun removeByDocuments(workspaceId: String, documentIds: Collection<String>) {
        if (documentIds.isEmpty()) {
            return
        }
        val placeholders = documentIds.joinToString(",") { "?" }
        val args = mutableListOf<Any>(workspaceId)
        args.addAll(documentIds)
        jdbcTemplate.update(
            """
            DELETE FROM document_favorite
            WHERE workspace_id = ? AND document_id IN ($placeholders)
            """.trimIndent(),
            *args.toTypedArray()
        )
    }
}

@Repository
class DocumentPersonalTopRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<DocumentPersonalTopRow> { rs: ResultSet, _: Int ->
        DocumentPersonalTopRow(
            workspaceId = rs.getString("workspace_id"),
            userId = rs.getString("user_id"),
            documentId = rs.getString("document_id"),
            ord = rs.getInt("ord"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    fun listByWorkspaceAndUser(workspaceId: String, userId: String): List<DocumentPersonalTopRow> = jdbcTemplate.query(
        """
        SELECT workspace_id, user_id, document_id, ord, created_at, updated_at
        FROM document_personal_top
        WHERE workspace_id = ? AND user_id = ?
        ORDER BY ord ASC, updated_at DESC
        """.trimIndent(),
        mapper,
        workspaceId,
        userId
    )

    fun replaceForWorkspaceAndUser(
        workspaceId: String,
        userId: String,
        orderedDocumentIds: List<String>
    ) {
        jdbcTemplate.update(
            """
            DELETE FROM document_personal_top
            WHERE workspace_id = ? AND user_id = ?
            """.trimIndent(),
            workspaceId,
            userId
        )
        val now = LocalDateTime.now()
        orderedDocumentIds.forEachIndexed { index, documentId ->
            jdbcTemplate.update(
                """
                INSERT INTO document_personal_top(workspace_id, user_id, document_id, ord, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                workspaceId,
                userId,
                documentId,
                index,
                now,
                now
            )
        }
    }

    fun removeByDocuments(workspaceId: String, documentIds: Collection<String>) {
        if (documentIds.isEmpty()) {
            return
        }
        val placeholders = documentIds.joinToString(",") { "?" }
        val args = mutableListOf<Any>(workspaceId)
        args.addAll(documentIds)
        jdbcTemplate.update(
            """
            DELETE FROM document_personal_top
            WHERE workspace_id = ? AND document_id IN ($placeholders)
            """.trimIndent(),
            *args.toTypedArray()
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

