package com.autodoctree.api.domain

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.AuditLogRepository
import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.EmbeddingRow
import com.autodoctree.api.db.EmbeddingRepository
import com.autodoctree.api.db.FeedbackRepository
import com.autodoctree.api.db.OutboxRepository
import com.autodoctree.api.db.StageExecutionRepository
import com.autodoctree.api.db.TreeMembershipRow
import com.autodoctree.api.db.TreeNodeRow
import com.autodoctree.api.db.TreeRepository
import com.autodoctree.api.db.TreeSnapshotRow
import com.autodoctree.api.infra.BadRequestException
import com.autodoctree.api.infra.NotFoundException
import com.autodoctree.api.infra.requireEditor
import com.autodoctree.api.infra.requireOwner
import com.autodoctree.api.tenant.WorkspaceContext
import com.autodoctree.api.worker.EmbeddingProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TreeService(
    private val documentRepository: DocumentRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val treeRepository: TreeRepository,
    private val feedbackRepository: FeedbackRepository,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val treeProperties: TreeProperties,
    private val featureFlags: FeatureFlags,
    private val neighborBuilder: NeighborBuilder,
    private val treeClusterer: TreeClusterer,
    private val treeLabeler: TreeLabeler,
    private val treePersonalizationEngine: TreePersonalizationEngine,
    private val embeddingProvider: EmbeddingProvider,
    private val rebuildDebounceQueue: RebuildDebounceQueue
) {

    @Transactional
    fun rebuildWorkspace(workspaceId: String, actorUserId: String? = null, manual: Boolean = false): TreeSnapshotRow {
        val documents = documentRepository.listWorkspaceDocuments(workspaceId)
        val active = treeRepository.findActiveSnapshot(workspaceId)
        val activeNodes = active?.let { treeRepository.listNodes(workspaceId, it.id) } ?: emptyList()
        val activeMemberships = active?.let { treeRepository.listMemberships(workspaceId, it.id) } ?: emptyList()

        val lockedNodes = activeNodes.filter { it.locked }
        val lockedNodeById = lockedNodes.associateBy { it.id }
        val nodeById = activeNodes.associateBy { it.id }

        val lockedLabelByDocument = activeMemberships
            .filter { lockedNodeById.containsKey(it.nodeId) }
            .associate { membership ->
                val label = lockedNodeById.getValue(membership.nodeId).label
                membership.documentId to label
            }

        val lockedParentLabelByLeaf = lockedNodes.associate { node ->
            val parentLabel = node.parentId?.let { parentId -> nodeById[parentId]?.label }
            node.label to parentLabel
        }

        val feedbackEvents = feedbackRepository.listByWorkspace(workspaceId, 200)
        val personalizationModel = if (featureFlags.autoTree) {
            treePersonalizationEngine.buildModel(
                feedbackEvents = feedbackEvents,
                activeNodes = activeNodes,
                documents = documents,
                tokenizer = treeLabeler::tokenize
            )
        } else {
            PersonalizationModel(
                docLabelScores = emptyMap(),
                keywordLabelScores = emptyMap(),
                minScore = Double.MAX_VALUE
            )
        }

        val assignment = mutableMapOf<String, String>()
        val personalizedDocIds = mutableSetOf<String>()

        documents.forEach { doc ->
            val forced = lockedLabelByDocument[doc.id]
            if (forced != null) {
                assignment[doc.id] = forced
                return@forEach
            }

            val preferred = personalizationModel.preferredLabelFor(doc, treeLabeler::tokenize)
            if (preferred != null) {
                assignment[doc.id] = preferred
                personalizedDocIds += doc.id
            }
        }

        val embeddingByDocumentId = embeddingRepository
            .listDocEmbeddings(workspaceId, embeddingProvider.modelVersion())
            .associateBy { it.documentId }
        val remaining = documents.filterNot { assignment.containsKey(it.id) }
        if (remaining.isNotEmpty()) {
            val graph = neighborBuilder.build(
                workspaceId = workspaceId,
                documents = remaining,
                embeddings = embeddingByDocumentId,
                topK = treeProperties.neighborTopK,
                minSimilarity = treeProperties.neighborMinSimilarity
            )

            val clusters = treeClusterer.cluster(
                documents = remaining,
                graph = graph,
                maxClusterSize = treeProperties.maxClusterSize
            )

            val labelsByCluster = treeLabeler.labelClusters(
                workspaceDocuments = documents,
                clusters = clusters
            )

            clusters.forEach { cluster ->
                val label = labelsByCluster[cluster.id] ?: "general"
                cluster.documentIds.forEach { docId ->
                    assignment[docId] = label
                }
            }
        }

        documents.forEach { doc ->
            assignment.putIfAbsent(doc.id, "general")
        }

        val previousDocToLabel = activeMemberships.associate { membership ->
            val node = activeNodes.firstOrNull { it.id == membership.nodeId }
            membership.documentId to (node?.label ?: "")
        }

        val movedCount = assignment.entries.count { (docId, newLabel) ->
            previousDocToLabel[docId] != null && previousDocToLabel[docId] != newLabel
        }
        val movedRatio = if (assignment.isEmpty()) 0.0 else movedCount.toDouble() / assignment.size.toDouble()

        val churnCount = movedCount
        val nodeRenameCount = TreeSnapshotMetrics.computeNodeRenameCount(
            activeNodes = activeNodes,
            newLabels = assignment.values.toSet()
        )
        val nextStatus = if (manual || active == null || movedRatio <= 0.35) "ACTIVE" else "RECOMMENDED"

        if (nextStatus == "ACTIVE") {
            treeRepository.markAllSnapshotsRecommended(workspaceId)
        }

        val snapshot = treeRepository.createSnapshot(
            workspaceId = workspaceId,
            status = nextStatus,
            movedRatio = movedRatio,
            churnCount = churnCount,
            nodeRenameCount = nodeRenameCount
        )

        val root = treeRepository.insertNode(
            workspaceId = workspaceId,
            snapshotId = snapshot.id,
            parentId = null,
            label = "AutoDoc",
            depth = 0,
            locked = false
        )

        val labels = assignment.values.toMutableSet().apply {
            addAll(lockedNodes.map { it.label })
        }.toList().sorted()

        val topLabelByLeaf = labels.associateWith { leafLabel ->
            val lockedParent = lockedParentLabelByLeaf[leafLabel]
            if (!lockedParent.isNullOrBlank() && lockedParent != "AutoDoc") {
                lockedParent
            } else {
                treeLabeler.topLevelLabel(leafLabel)
            }
        }

        val topNodes = mutableMapOf<String, TreeNodeRow>()
        topLabelByLeaf.values.toSet().sorted().forEach { topLabel ->
            topNodes[topLabel] = treeRepository.insertNode(
                workspaceId = workspaceId,
                snapshotId = snapshot.id,
                parentId = root.id,
                label = topLabel,
                depth = 1,
                locked = false
            )
        }

        val labelToNode = mutableMapOf<String, TreeNodeRow>()
        labels.forEach { label ->
            val locked = lockedNodes.any { it.label == label }
            val topLabel = topLabelByLeaf[label] ?: treeLabeler.topLevelLabel(label)
            val parent = topNodes[topLabel]
            if (!locked && parent != null && label == topLabel) {
                labelToNode[label] = parent
                return@forEach
            }
            val node = treeRepository.insertNode(
                workspaceId = workspaceId,
                snapshotId = snapshot.id,
                parentId = parent?.id ?: root.id,
                label = label,
                depth = if (parent == null) 1 else 2,
                locked = locked
            )
            labelToNode[label] = node
        }

        documents.forEach { doc ->
            val label = assignment[doc.id] ?: "general"
            val node = labelToNode[label] ?: return@forEach
            val rationale = mapOf(
                "keywords" to treeLabeler.keywords(doc.title + " " + (doc.bodyText ?: ""), 5),
                "similar_docs" to findSimilarDocs(doc.id, embeddingByDocumentId, 3),
                "signals" to buildSignals(
                    wasLocked = lockedLabelByDocument.containsKey(doc.id),
                    personalized = personalizedDocIds.contains(doc.id)
                )
            )
            treeRepository.insertMembership(
                workspaceId = workspaceId,
                snapshotId = snapshot.id,
                nodeId = node.id,
                documentId = doc.id,
                rationaleJson = objectMapper.writeValueAsString(rationale)
            )
        }

        if (actorUserId != null) {
            auditService.write(
                workspaceId,
                actorUserId,
                "tree.rebuild",
                mapOf(
                    "snapshot_id" to snapshot.id,
                    "status" to snapshot.status,
                    "moved_ratio" to movedRatio,
                    "churn_count" to churnCount
                )
            )
        }

        return snapshot
    }

    fun getActiveTree(context: WorkspaceContext): Map<String, Any?> {
        val active = treeRepository.findActiveSnapshot(context.workspaceId)
            ?: return mapOf("snapshot_id" to null, "status" to "EMPTY", "nodes" to emptyList<Any>())
        val nodes = treeRepository.listNodes(context.workspaceId, active.id)
        val memberships = treeRepository.listMemberships(context.workspaceId, active.id)
        val documentsById = documentRepository.listWorkspaceDocuments(context.workspaceId).associateBy { it.id }
        val docsByNode = memberships.groupBy { it.nodeId }.mapValues { it.value.map(TreeMembershipRow::documentId) }
        return mapOf(
            "snapshot_id" to active.id,
            "status" to active.status,
            "nodes" to nodes.map {
                val nodeDocumentIds = docsByNode[it.id] ?: emptyList<String>()
                mapOf(
                    "id" to it.id,
                    "parent_id" to it.parentId,
                    "label" to it.label,
                    "locked" to it.locked,
                    "documents" to nodeDocumentIds,
                    "document_summaries" to nodeDocumentIds.map { documentId ->
                        mapOf(
                            "id" to documentId,
                            "title" to (documentsById[documentId]?.title ?: documentId)
                        )
                    }
                )
            }
        )
    }

    fun listSnapshots(context: WorkspaceContext): Map<String, Any?> {
        return mapOf(
            "items" to treeRepository.listSnapshots(context.workspaceId).map {
                mapOf(
                    "id" to it.id,
                    "status" to it.status,
                    "moved_ratio" to it.movedRatio,
                    "churn_count" to it.churnCount,
                    "node_rename_count" to it.nodeRenameCount,
                    "created_at" to it.createdAt.toString()
                )
            }
        )
    }

    @Transactional
    fun requestRebuild(context: WorkspaceContext, mode: String): Map<String, Any?> {
        requireEditor(context)
        val manual = mode.equals("IMMEDIATE", ignoreCase = true)
        if (!manual) {
            rebuildDebounceQueue.request(context.workspaceId, "MANUAL_DEBOUNCED_REQUEST")
            return mapOf(
                "snapshot_id" to null,
                "status" to "QUEUED",
                "pending_count" to rebuildDebounceQueue.pendingCount(context.workspaceId)
            )
        }
        val snapshot = rebuildWorkspace(context.workspaceId, context.userId, manual = manual)
        return mapOf("snapshot_id" to snapshot.id, "status" to snapshot.status)
    }

    @Transactional
    fun activateSnapshot(context: WorkspaceContext, snapshotId: String) {
        requireEditor(context)
        val snapshot = treeRepository.findSnapshotByWorkspace(context.workspaceId, snapshotId) ?: throw NotFoundException()
        treeRepository.activateSnapshot(context.workspaceId, snapshot.id, context.userId)
        auditService.write(
            context.workspaceId,
            context.userId,
            "snapshot.activated",
            mapOf("snapshot_id" to snapshot.id)
        )
    }

    @Transactional
    fun lockNode(context: WorkspaceContext, nodeId: String, locked: Boolean) {
        requireEditor(context)
        val node = treeRepository.findNodeByWorkspace(context.workspaceId, nodeId) ?: throw NotFoundException()
        treeRepository.updateNodeLock(context.workspaceId, node.id, locked)
        auditService.write(
            context.workspaceId,
            context.userId,
            "node.lock_changed",
            mapOf("node_id" to nodeId, "locked" to locked)
        )
    }

    fun explain(context: WorkspaceContext, documentId: String): Map<String, Any?> {
        val membership = treeRepository.findMembershipByWorkspaceAndDocument(context.workspaceId, documentId)
        val fallback = mapOf(
            "keywords" to emptyList<String>(),
            "similar_docs" to emptyList<Map<String, Any?>>(),
            "signals" to emptyList<String>()
        )
        val rationale = membership?.let { parseRationale(it.rationaleJson) } ?: fallback
        return mapOf(
            "document_id" to documentId,
            "node_id" to membership?.nodeId,
            "rationale" to rationale
        )
    }

    private fun buildSignals(wasLocked: Boolean, personalized: Boolean): List<String> {
        val signals = mutableListOf<String>()
        if (wasLocked) signals += "LOCKED_NODE"
        if (personalized) signals += "PERSONALIZED_MOVE_SIGNAL"
        if (signals.isEmpty()) {
            signals += "CLUSTER_DEFAULT"
        }
        return signals
    }

    private fun parseRationale(json: String): Map<String, Any?> {
        return runCatching {
            val raw = objectMapper.readValue(json, Map::class.java) as Map<*, *>
            raw.entries.associate { (key, value) -> key.toString() to value }
        }.getOrElse {
            mapOf(
                "keywords" to emptyList<String>(),
                "similar_docs" to emptyList<Map<String, Any?>>(),
                "signals" to emptyList<String>()
            )
        }
    }

    private fun findSimilarDocs(documentId: String, embeddings: Map<String, EmbeddingRow>, limit: Int): List<Map<String, Any?>> {
        val source = embeddings[documentId] ?: return emptyList()
        val sourceVector = objectMapper.readValue(source.vectorJson, List::class.java)
            .mapNotNull { number -> (number as? Number)?.toDouble() }

        val scores = embeddings.values
            .asSequence()
            .filter { it.documentId != documentId }
            .mapNotNull { candidate ->
                val vector = objectMapper.readValue(candidate.vectorJson, List::class.java)
                    .mapNotNull { number -> (number as? Number)?.toDouble() }
                if (vector.isEmpty()) {
                    return@mapNotNull null
                }
                val similarity = cosine(sourceVector, vector)
                mapOf(
                    "document_id" to candidate.documentId,
                    "title" to candidate.documentId,
                    "similarity" to similarity
                )
            }
            .sortedByDescending { it["similarity"] as Double }
            .take(limit)
            .toList()

        return scores
    }

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0
        var dot = 0.0
        var an = 0.0
        var bn = 0.0
        for (i in 0 until size) {
            dot += a[i] * b[i]
            an += a[i] * a[i]
            bn += b[i] * b[i]
        }
        if (an == 0.0 || bn == 0.0) return 0.0
        return dot / (kotlin.math.sqrt(an) * kotlin.math.sqrt(bn))
    }
}

@Service
class FeedbackService(
    private val documentRepository: DocumentRepository,
    private val treeRepository: TreeRepository,
    private val feedbackRepository: FeedbackRepository,
    private val outboxService: OutboxService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun move(context: WorkspaceContext, documentId: String, fromNodeId: String?, toNodeId: String) {
        requireEditor(context)
        documentRepository.findByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        val toNode = treeRepository.findNodeByWorkspace(context.workspaceId, toNodeId) ?: throw NotFoundException()
        treeRepository.moveDocumentInActiveSnapshot(context.workspaceId, documentId, toNode.id)

        val payload = mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "document_id" to documentId,
            "from_node_id" to fromNodeId,
            "to_node_id" to toNode.id
        )
        val payloadJson = objectMapper.writeValueAsString(payload)
        feedbackRepository.insert(context.workspaceId, context.userId, "MOVE", payloadJson)
        outboxService.enqueue(context.workspaceId, documentId, "FeedbackRecorded", payload)
        auditService.write(context.workspaceId, context.userId, "feedback.move", payload)
    }

    @Transactional
    fun rename(context: WorkspaceContext, nodeId: String, oldLabel: String?, newLabel: String) {
        requireEditor(context)
        if (newLabel.isBlank()) {
            throw BadRequestException("new_label must not be blank")
        }
        val node = treeRepository.findNodeByWorkspace(context.workspaceId, nodeId) ?: throw NotFoundException()
        treeRepository.renameNode(context.workspaceId, node.id, newLabel.take(80))

        val payload = mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "node_id" to nodeId,
            "old_label" to (oldLabel ?: node.label),
            "new_label" to newLabel.take(80)
        )
        feedbackRepository.insert(context.workspaceId, context.userId, "RENAME", objectMapper.writeValueAsString(payload))
        outboxService.enqueue(context.workspaceId, null, "FeedbackRecorded", payload)
        auditService.write(context.workspaceId, context.userId, "feedback.rename", payload)
    }
}

@Service
class AdminService(
    private val outboxRepository: OutboxRepository,
    private val stageExecutionRepository: StageExecutionRepository,
    private val auditLogRepository: AuditLogRepository,
    private val outboxService: OutboxService,
    private val auditService: AuditService
) {

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

    fun listAudit(context: WorkspaceContext, type: String?): Map<String, Any?> {
        requireOwner(context)
        val items = auditLogRepository.listByWorkspace(context.workspaceId, type).map {
            mapOf(
                "id" to it.id,
                "workspace_id" to it.workspaceId,
                "actor_user_id" to it.actorUserId,
                "action" to it.action,
                "payload" to it.payloadJson,
                "created_at" to it.createdAt.toString()
            )
        }
        return mapOf("items" to items)
    }

    private fun inferStage(eventType: String): String = when (eventType) {
        "AttachmentUploaded" -> "INGEST"
        "DocumentSaved", "DocumentUpdated" -> "INGEST"
        "DocumentDeleted" -> "INDEX"
        else -> "TREE"
    }
}
