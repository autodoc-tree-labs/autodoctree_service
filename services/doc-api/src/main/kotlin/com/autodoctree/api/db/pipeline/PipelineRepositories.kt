package com.autodoctree.api.db.pipeline

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

