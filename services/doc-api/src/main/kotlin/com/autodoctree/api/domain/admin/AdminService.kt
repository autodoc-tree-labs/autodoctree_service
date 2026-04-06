package com.autodoctree.api.domain.admin

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.document.AttachmentRepository
import com.autodoctree.api.db.admin.AuditLogRepository
import com.autodoctree.api.db.tree.ConceptPrototypeRepository
import com.autodoctree.api.db.ConceptPrototypeRow
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.document.DocumentRepository
import com.autodoctree.api.db.document.DocumentSectionRepository
import com.autodoctree.api.db.EmbeddingRow
import com.autodoctree.api.db.pipeline.EmbeddingRepository
import com.autodoctree.api.db.FeedbackEventRow
import com.autodoctree.api.db.tree.FeedbackRepository
import com.autodoctree.api.db.pipeline.OutboxRepository
import com.autodoctree.api.db.pipeline.StageExecutionRepository
import com.autodoctree.api.db.TreeMembershipRow
import com.autodoctree.api.db.TreeNodeRow
import com.autodoctree.api.db.tree.WorkspaceTreePolicyRepository
import com.autodoctree.api.db.tree.TreeRepository
import com.autodoctree.api.db.TreeSnapshotRow
import com.autodoctree.api.db.tree.UserRuleRepository
import com.autodoctree.api.infra.BadRequestException
import com.autodoctree.api.infra.ForbiddenException
import com.autodoctree.api.infra.NotFoundException
import com.autodoctree.api.infra.requireEditor
import com.autodoctree.api.infra.requireOwner
import com.autodoctree.api.llm.LlmTextGenerator
import com.autodoctree.api.structure.StructureWorkerClient
import com.autodoctree.api.tenant.WorkspaceContext
import com.autodoctree.api.worker.EmbeddingProvider
import com.autodoctree.api.worker.EmbeddingQualityScorer
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Statistic
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.sqrt
import com.autodoctree.api.domain.document.AuditService
import com.autodoctree.api.domain.document.OutboxService
import com.autodoctree.api.domain.tree.TreeService
import com.autodoctree.api.domain.tree.UserRuleMatcher
import com.autodoctree.api.domain.tree.UserRuleMatchContext
import com.autodoctree.api.domain.tree.ResolvedUserRule

@Service
class AdminService(
    private val outboxRepository: OutboxRepository,
    private val stageExecutionRepository: StageExecutionRepository,
    private val auditLogRepository: AuditLogRepository,
    private val documentRepository: DocumentRepository,
    private val attachmentRepository: AttachmentRepository,
    private val outboxService: OutboxService,
    private val auditService: AuditService,
    private val treeService: TreeService,
    private val treeRepository: TreeRepository,
    private val workspaceTreePolicyRepository: WorkspaceTreePolicyRepository,
    private val userRuleRepository: UserRuleRepository,
    private val userRuleMatcher: UserRuleMatcher,
    private val treeProperties: TreeProperties,
    private val featureFlags: FeatureFlags,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {
    private val debugNeighborsCalledCounter = meterRegistry.counter("debug_neighbors_called_total")
    private val debugDocumentCalledCounter = meterRegistry.counter("debug_document_called_total")
    private val debugClusterCalledCounter = meterRegistry.counter("debug_cluster_called_total")
    private val debugRebuildCalledCounter = meterRegistry.counter("debug_rebuild_called_total")
    private val auditListCounter = meterRegistry.counter("admin.audit.list_total")
    private val auditListSizeSummary = meterRegistry.summary("admin.audit.list_size")

    fun listJobs(context: WorkspaceContext, documentId: String?): Map<String, Any?> {
        requireOwner(context)
        val outbox = outboxRepository.listByWorkspace(context.workspaceId, documentId)
        val stageExecutions = stageExecutionRepository.listByWorkspace(context.workspaceId, documentId)
        val items = mutableListOf<Map<String, Any?>>()

        outbox.forEach {
            items += mapOf(
                "id" to it.id,
                "workspace_id" to it.workspaceId,
                "document_id" to it.documentId,
                "stage" to inferStage(it.eventType),
                "status" to it.status,
                "retries" to it.retryCount,
                "created_at" to it.createdAt.toString(),
                "source" to "OUTBOX"
            )
        }

        stageExecutions.forEach {
            items += mapOf(
                "id" to it.id,
                "workspace_id" to it.workspaceId,
                "document_id" to it.documentId,
                "stage" to it.stage.name,
                "status" to it.status.name,
                "retries" to it.retries,
                "created_at" to it.createdAt.toString(),
                "source" to "STAGE_EXECUTION"
            )
        }

        return mapOf("items" to items.sortedByDescending { it["created_at"] as String })
    }

    @Transactional
    fun retryStage(context: WorkspaceContext, documentId: String, stage: String) {
        requireOwner(context)
        val payload = mapOf(
            "document_id" to documentId,
            "stage" to stage.uppercase()
        )
        outboxService.enqueue(context.workspaceId, documentId, "StageRetry", payload)
        auditService.write(context.workspaceId, context.userId, "admin.retry", payload)
    }

    fun listAudit(
        context: WorkspaceContext,
        type: String?,
        actorUserId: String?,
        query: String?,
        sort: String?,
        limit: Int
    ): Map<String, Any?> {
        requireOwner(context)
        val normalizedSort = when (sort?.trim()?.lowercase()) {
            null, "", "desc" -> "desc"
            "asc" -> "asc"
            else -> throw BadRequestException("sort must be asc or desc")
        }
        val boundedLimit = limit.coerceIn(1, 500)
        val items = auditLogRepository.listByWorkspace(
            workspaceId = context.workspaceId,
            type = type,
            actorUserId = actorUserId,
            query = query,
            sort = normalizedSort,
            limit = boundedLimit
        ).map {
            mapOf(
                "id" to it.id,
                "workspace_id" to it.workspaceId,
                "actor_user_id" to it.actorUserId,
                "action" to it.action,
                "payload" to parseAuditPayload(it.payloadJson),
                "created_at" to it.createdAt.toString()
            )
        }
        auditListCounter.increment()
        auditListSizeSummary.record(items.size.toDouble())
        return mapOf(
            "items" to items,
            "sort" to normalizedSort,
            "limit" to boundedLimit
        )
    }

    private fun parseAuditPayload(payloadJson: String): Map<String, Any?> {
        return runCatching {
            val raw = objectMapper.readValue(payloadJson, Map::class.java) as Map<*, *>
            raw.entries.associate { (key, value) -> key.toString() to value }
        }.getOrElse {
            mapOf("raw" to payloadJson.take(240))
        }
    }

    fun debugNeighbors(context: WorkspaceContext, documentId: String): Map<String, Any?> {
        requireOwner(context)
        if (!featureFlags.adminTreeDebug) {
            throw ForbiddenException("tree debug feature is disabled")
        }
        debugNeighborsCalledCounter.increment()
        return treeService.debugNeighbors(context.workspaceId, documentId)
    }

    fun debugDocument(context: WorkspaceContext, documentId: String, topN: Int): Map<String, Any?> {
        requireOwner(context)
        if (!featureFlags.adminTreeDebug) {
            throw ForbiddenException("tree debug feature is disabled")
        }
        debugDocumentCalledCounter.increment()
        return treeService.debugDocument(context.workspaceId, documentId, topN)
    }

    fun debugCluster(context: WorkspaceContext, clusterId: String): Map<String, Any?> {
        requireOwner(context)
        if (!featureFlags.adminTreeDebug) {
            throw ForbiddenException("tree debug feature is disabled")
        }
        debugClusterCalledCounter.increment()
        return treeService.debugCluster(context.workspaceId, clusterId)
    }

    fun debugRebuild(context: WorkspaceContext, snapshotId: String): Map<String, Any?> {
        requireOwner(context)
        if (!featureFlags.adminTreeDebug) {
            throw ForbiddenException("tree debug feature is disabled")
        }
        debugRebuildCalledCounter.increment()
        return treeService.debugRebuild(context.workspaceId, snapshotId)
    }

    fun clusterStats(context: WorkspaceContext): Map<String, Any?> {
        requireOwner(context)
        if (!featureFlags.adminTreeDebug) {
            throw ForbiddenException("tree debug feature is disabled")
        }
        val active = treeRepository.findActiveSnapshot(context.workspaceId)
        if (active == null) {
            return mapOf(
                "snapshot_id" to null,
                "status" to "EMPTY",
                "cluster_count" to 0,
                "avg_cluster_size" to 0.0,
                "neighbor_edges_total" to summaryTotal("neighbor_edges_total"),
                "edges_filtered_total" to summaryTotal("edges_filtered_total"),
                "label_filtered_total" to counterTotal("label_filtered_total"),
                "avg_label_length" to summaryAverage("avg_label_length"),
                "tree_rebuild_duration_ms" to summaryAverage("tree_rebuild_duration_ms"),
                "auto_ratio" to summaryAverage("auto_ratio"),
                "recommend_ratio" to summaryAverage("recommend_ratio"),
                "moved_ratio" to summaryAverage("moved_ratio"),
                "churn_ratio" to summaryAverage("churn_ratio")
            )
        }

        val nodes = treeRepository.listNodes(context.workspaceId, active.id)
        val memberships = treeRepository.listMemberships(context.workspaceId, active.id)
        val leafNodes = nodes.filter { it.depth >= 2 }
            .ifEmpty { nodes.filter { it.depth == 1 && it.label != "AutoDoc" } }
        val docsByNode = memberships.groupBy { it.nodeId }
        val avgClusterSize = if (leafNodes.isEmpty()) {
            0.0
        } else {
            leafNodes.map { node -> docsByNode[node.id].orEmpty().size.toDouble() }.average()
        }

        return mapOf(
            "snapshot_id" to active.id,
            "status" to active.status,
            "cluster_count" to leafNodes.size,
            "avg_cluster_size" to avgClusterSize,
            "neighbor_edges_total" to summaryTotal("neighbor_edges_total"),
            "edges_filtered_total" to summaryTotal("edges_filtered_total"),
            "cluster_count_metric" to summaryTotal("cluster_count"),
            "avg_cluster_size_metric" to summaryAverage("avg_cluster_size"),
            "label_filtered_total" to counterTotal("label_filtered_total"),
            "avg_label_length" to summaryAverage("avg_label_length"),
            "tree_rebuild_duration_ms" to summaryAverage("tree_rebuild_duration_ms"),
            "auto_ratio" to summaryAverage("auto_ratio"),
            "recommend_ratio" to summaryAverage("recommend_ratio"),
            "moved_ratio" to summaryAverage("moved_ratio"),
            "churn_ratio" to summaryAverage("churn_ratio")
        )
    }

    fun getTreePolicy(context: WorkspaceContext): Map<String, Any?> {
        requireOwner(context)
        val row = workspaceTreePolicyRepository.findByWorkspace(context.workspaceId)
        return mapOf(
            "workspace_id" to context.workspaceId,
            "auto_threshold" to (row?.autoThreshold ?: treeProperties.assignAutoThreshold),
            "recommend_threshold" to (row?.recommendThreshold ?: treeProperties.assignRecommendThreshold),
            "quarantine_enabled" to (row?.quarantineEnabled ?: treeProperties.assignQuarantineEnabled),
            "reranker_enabled" to (row?.rerankerEnabled ?: treeProperties.assignRerankerEnabled),
            "source" to if (row == null) "DEFAULT" else "OVERRIDE",
            "updated_by" to row?.updatedBy,
            "updated_at" to row?.updatedAt?.toString()
        )
    }

    @Transactional
    fun updateTreePolicy(
        context: WorkspaceContext,
        autoThreshold: Double,
        recommendThreshold: Double,
        quarantineEnabled: Boolean,
        rerankerEnabled: Boolean
    ): Map<String, Any?> {
        requireOwner(context)
        val auto = autoThreshold.coerceIn(0.0, 1.0)
        val recommend = recommendThreshold.coerceIn(0.0, 1.0)
        if (recommend > auto) {
            throw BadRequestException("recommend_threshold must be <= auto_threshold")
        }
        val updated = workspaceTreePolicyRepository.upsert(
            workspaceId = context.workspaceId,
            autoThreshold = auto,
            recommendThreshold = recommend,
            quarantineEnabled = quarantineEnabled,
            rerankerEnabled = rerankerEnabled,
            updatedBy = context.userId
        )
        val payload = mapOf(
            "workspace_id" to context.workspaceId,
            "auto_threshold" to updated.autoThreshold,
            "recommend_threshold" to updated.recommendThreshold,
            "quarantine_enabled" to updated.quarantineEnabled,
            "reranker_enabled" to updated.rerankerEnabled
        )
        auditService.write(context.workspaceId, context.userId, "admin.tree_policy.updated", payload)
        return payload + mapOf(
            "source" to "OVERRIDE",
            "updated_by" to updated.updatedBy,
            "updated_at" to updated.updatedAt.toString()
        )
    }

    fun listUserRules(context: WorkspaceContext): Map<String, Any?> {
        requireOwner(context)
        if (!featureFlags.userRulesV1) {
            throw ForbiddenException("user rules feature is disabled")
        }
        val nodesById = treeRepository
            .findActiveSnapshot(context.workspaceId)
            ?.let { snapshot -> treeRepository.listNodes(context.workspaceId, snapshot.id) }
            .orEmpty()
            .associateBy { it.id }
        return mapOf(
            "items" to userRuleRepository.listByWorkspace(context.workspaceId).map { rule ->
                mapOf(
                    "id" to rule.id,
                    "rule_type" to rule.ruleType,
                    "rule_value" to rule.ruleValue,
                    "rule_effect" to rule.ruleEffect,
                    "node_id" to rule.nodeId,
                    "node_label" to (nodesById[rule.nodeId]?.label
                        ?: treeRepository.findNodeByWorkspace(context.workspaceId, rule.nodeId)?.label),
                    "enabled" to rule.enabled,
                    "created_at" to rule.createdAt.toString()
                )
            }
        )
    }

    @Transactional
    fun createUserRule(
        context: WorkspaceContext,
        ruleType: String,
        ruleValue: String,
        nodeId: String,
        ruleEffect: String?
    ): Map<String, Any?> {
        requireOwner(context)
        if (!featureFlags.userRulesV1) {
            throw ForbiddenException("user rules feature is disabled")
        }
        val normalizedType = validateRuleType(ruleType)
        val normalizedValue = validateRuleValue(ruleValue)
        val normalizedEffect = validateRuleEffect(ruleEffect)
        val node = treeRepository.findNodeByWorkspace(context.workspaceId, nodeId) ?: throw NotFoundException()
        val rule = userRuleRepository.create(
            workspaceId = context.workspaceId,
            ruleType = normalizedType,
            ruleValue = normalizedValue.take(255),
            ruleEffect = normalizedEffect,
            nodeId = node.id,
            createdBy = context.userId
        )
        auditService.write(
            context.workspaceId,
            context.userId,
            "admin.rule.created",
            mapOf(
                "rule_id" to rule.id,
                "rule_type" to rule.ruleType,
                "rule_effect" to rule.ruleEffect,
                "node_id" to node.id
            )
        )
        return mapOf(
            "id" to rule.id,
            "rule_type" to rule.ruleType,
            "rule_value" to rule.ruleValue,
            "rule_effect" to rule.ruleEffect,
            "node_id" to rule.nodeId
        )
    }

    @Transactional
    fun updateUserRule(
        context: WorkspaceContext,
        ruleId: String,
        ruleType: String,
        ruleValue: String,
        nodeId: String,
        ruleEffect: String?
    ): Map<String, Any?> {
        requireOwner(context)
        if (!featureFlags.userRulesV1) {
            throw ForbiddenException("user rules feature is disabled")
        }
        val existing = userRuleRepository.findByWorkspaceAndId(context.workspaceId, ruleId) ?: throw NotFoundException()
        val normalizedType = validateRuleType(ruleType)
        val normalizedValue = validateRuleValue(ruleValue)
        val normalizedEffect = validateRuleEffect(ruleEffect)
        val node = treeRepository.findNodeByWorkspace(context.workspaceId, nodeId) ?: throw NotFoundException()
        val updated = userRuleRepository.update(
            workspaceId = context.workspaceId,
            ruleId = existing.id,
            ruleType = normalizedType,
            ruleValue = normalizedValue.take(255),
            ruleEffect = normalizedEffect,
            nodeId = node.id
        ) ?: throw NotFoundException()
        auditService.write(
            context.workspaceId,
            context.userId,
            "admin.rule.updated",
            mapOf(
                "rule_id" to updated.id,
                "rule_type" to updated.ruleType,
                "rule_effect" to updated.ruleEffect,
                "node_id" to updated.nodeId
            )
        )
        return mapOf(
            "id" to updated.id,
            "rule_type" to updated.ruleType,
            "rule_value" to updated.ruleValue,
            "rule_effect" to updated.ruleEffect,
            "node_id" to updated.nodeId
        )
    }

    fun previewUserRule(
        context: WorkspaceContext,
        documentId: String,
        ruleType: String,
        ruleValue: String,
        nodeId: String,
        ruleEffect: String?
    ): Map<String, Any?> {
        requireOwner(context)
        if (!featureFlags.userRulesV1) {
            throw ForbiddenException("user rules feature is disabled")
        }
        val document = documentRepository.findByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        val node = treeRepository.findNodeByWorkspace(context.workspaceId, nodeId) ?: throw NotFoundException()
        val normalizedType = validateRuleType(ruleType)
        val normalizedValue = validateRuleValue(ruleValue)
        val normalizedEffect = validateRuleEffect(ruleEffect)
        val contextData = UserRuleMatchContext(
            filenameExtensions = attachmentRepository
                .listByWorkspaceAndDocument(context.workspaceId, document.id)
                .mapNotNull { attachment -> extractFilenameExtension(attachment.filename) }
                .toSet(),
            tags = userRuleMatcher.extractTags(document)
        )
        val previewRule = ResolvedUserRule(
            id = "preview",
            ruleType = normalizedType,
            ruleValue = normalizedValue,
            targetLabel = node.label,
            ruleEffect = normalizedEffect
        )
        val matched = userRuleMatcher.match(document, listOf(previewRule), contextData) != null
        return mapOf(
            "document_id" to document.id,
            "rule_type" to normalizedType,
            "rule_value" to normalizedValue,
            "rule_effect" to normalizedEffect,
            "matched" to matched,
            "target_node_id" to if (matched) node.id else null,
            "target_node_label" to if (matched) node.label else null
        )
    }

    @Transactional
    fun deleteUserRule(context: WorkspaceContext, ruleId: String) {
        requireOwner(context)
        if (!featureFlags.userRulesV1) {
            throw ForbiddenException("user rules feature is disabled")
        }
        userRuleRepository.delete(context.workspaceId, ruleId)
        auditService.write(
            context.workspaceId,
            context.userId,
            "admin.rule.deleted",
            mapOf("rule_id" to ruleId)
        )
    }

    private fun validateRuleType(ruleType: String): String {
        val normalizedType = userRuleMatcher.normalizeRuleType(ruleType)
        if (normalizedType !in UserRuleMatcher.SUPPORTED_RULE_TYPES) {
            throw BadRequestException("unsupported rule_type")
        }
        return normalizedType
    }

    private fun validateRuleValue(ruleValue: String): String {
        val normalizedValue = userRuleMatcher.normalizeRuleValue(ruleValue)
        if (normalizedValue.isBlank()) {
            throw BadRequestException("rule_value must not be blank")
        }
        return normalizedValue
    }

    private fun validateRuleEffect(ruleEffect: String?): String {
        val normalizedEffect = ruleEffect?.trim()?.uppercase().orEmpty().ifBlank { "HARD" }
        if (normalizedEffect !in UserRuleMatcher.SUPPORTED_RULE_EFFECTS) {
            throw BadRequestException("unsupported rule_effect")
        }
        return normalizedEffect
    }

    private fun extractFilenameExtension(filename: String): String? {
        val leaf = filename.substringAfterLast('/').substringAfterLast('\\')
        val dotIndex = leaf.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == leaf.length - 1) {
            return null
        }
        return leaf.substring(dotIndex + 1).trim().lowercase().takeIf { it.isNotBlank() }
    }

    private fun counterTotal(name: String): Double {
        return meterRegistry.find(name).counters().sumOf { it.count() }
    }

    private fun summaryTotal(name: String): Double {
        val summaries = meterRegistry.find(name).summaries()
        if (summaries.isEmpty()) {
            return meterRegistry.find(name).meters().sumOf { meter ->
                meter.measure()
                    .firstOrNull { it.statistic == Statistic.COUNT || it.statistic == Statistic.TOTAL }
                    ?.value ?: 0.0
            }
        }
        return summaries.sumOf { it.totalAmount() }
    }

    private fun summaryAverage(name: String): Double {
        val summaries = meterRegistry.find(name).summaries()
        if (summaries.isEmpty()) {
            return 0.0
        }
        val count = summaries.sumOf { it.count() }
        if (count == 0L) {
            return 0.0
        }
        val total = summaries.sumOf { it.totalAmount() }
        return total / count.toDouble()
    }

    private fun inferStage(eventType: String): String = when (eventType) {
        "AttachmentUploaded" -> "INGEST"
        "DocumentSaved", "DocumentUpdated" -> "INGEST"
        "DocumentDeleted" -> "INDEX"
        else -> "TREE"
    }
}
