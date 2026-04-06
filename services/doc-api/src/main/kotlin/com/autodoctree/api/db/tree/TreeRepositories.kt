package com.autodoctree.api.db.tree

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

