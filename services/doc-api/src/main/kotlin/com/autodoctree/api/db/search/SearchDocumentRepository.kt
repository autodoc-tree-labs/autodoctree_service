package com.autodoctree.api.db.search

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
