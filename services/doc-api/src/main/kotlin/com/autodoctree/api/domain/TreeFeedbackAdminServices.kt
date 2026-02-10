package com.autodoctree.api.domain

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.AuditLogRepository
import com.autodoctree.api.db.DocumentRow
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
import com.autodoctree.api.db.UserRuleRepository
import com.autodoctree.api.infra.BadRequestException
import com.autodoctree.api.infra.ForbiddenException
import com.autodoctree.api.infra.NotFoundException
import com.autodoctree.api.infra.requireEditor
import com.autodoctree.api.infra.requireOwner
import com.autodoctree.api.tenant.WorkspaceContext
import com.autodoctree.api.worker.EmbeddingProvider
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Statistic
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.math.sqrt

@Service
class TreeService(
    private val documentRepository: DocumentRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val treeRepository: TreeRepository,
    private val feedbackRepository: FeedbackRepository,
    private val userRuleRepository: UserRuleRepository,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val treeProperties: TreeProperties,
    private val featureFlags: FeatureFlags,
    private val neighborBuilder: NeighborBuilder,
    private val treeClusterer: TreeClusterer,
    private val treeLabeler: TreeLabeler,
    private val treePersonalizationEngine: TreePersonalizationEngine,
    private val userRuleMatcher: UserRuleMatcher,
    private val embeddingProvider: EmbeddingProvider,
    private val rebuildDebounceQueue: RebuildDebounceQueue,
    meterRegistry: MeterRegistry
) {
    private val rebuildDurationSummary = meterRegistry.summary("tree_rebuild_duration_ms")
    private val feedbackAppliedCounter = meterRegistry.counter("feedback_applied_total")
    private val correctedRatioSummary = meterRegistry.summary("corrected_ratio")
    private val rulesAppliedCounter = meterRegistry.counter("rules_applied_total")
    private val lockedNodePreservedCounter = meterRegistry.counter("locked_node_preserved_total")
    private val movedRatioSummary = meterRegistry.summary("moved_ratio")
    private val churnRatioSummary = meterRegistry.summary("churn_ratio")

    @Transactional
    fun rebuildWorkspace(workspaceId: String, actorUserId: String? = null, manual: Boolean = false): TreeSnapshotRow {
        val startedAt = System.nanoTime()
        try {
            val documents = documentRepository.listWorkspaceDocuments(workspaceId)
            val documentsById = documents.associateBy { it.id }
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
            val lockedDocsByLabel = lockedLabelByDocument.entries.groupBy({ it.value }, { it.key })

            val lockedParentLabelByLeaf = lockedNodes.associate { node ->
                val parentLabel = node.parentId?.let { parentId -> nodeById[parentId]?.label }
                node.label to parentLabel
            }

            val embeddingByDocumentId = embeddingRepository
                .listDocEmbeddings(workspaceId, embeddingProvider.modelVersion())
                .associateBy { it.documentId }

            val feedbackEvents = feedbackRepository.listByWorkspace(workspaceId, 200)
            val personalizationModel = if (featureFlags.autoTree) {
                treePersonalizationEngine.buildModel(
                    feedbackEvents = feedbackEvents,
                    activeNodes = activeNodes,
                    documents = documents,
                    tokenizer = treeLabeler::tokenize,
                    embeddings = embeddingByDocumentId,
                    routingV2Enabled = featureFlags.feedbackRoutingV2
                )
            } else {
                emptyPersonalizationModel()
            }

            val assignment = mutableMapOf<String, String>()
            val personalizedDocIds = mutableSetOf<String>()
            val ruledDocIds = mutableSetOf<String>()

            documents.forEach { doc ->
                val forced = lockedLabelByDocument[doc.id]
                if (forced != null) {
                    assignment[doc.id] = forced
                }
            }

            val rules = if (featureFlags.userRulesV1) {
                resolveUserRules(workspaceId, activeNodes)
            } else {
                emptyList()
            }
            if (rules.isNotEmpty()) {
                documents
                    .filterNot { assignment.containsKey(it.id) }
                    .forEach { doc ->
                        val matched = userRuleMatcher.match(doc, rules)
                        if (matched != null) {
                            assignment[doc.id] = matched.targetLabel
                            ruledDocIds += doc.id
                            rulesAppliedCounter.increment()
                        }
                    }
            }

            documents
                .filterNot { assignment.containsKey(it.id) }
                .forEach { doc ->
                    val preferred = personalizationModel.preferredLabelFor(doc, treeLabeler::tokenize)
                    if (preferred != null) {
                        assignment[doc.id] = preferred
                        personalizedDocIds += doc.id
                    }
                }

            if (personalizedDocIds.isNotEmpty()) {
                feedbackAppliedCounter.increment(personalizedDocIds.size.toDouble())
            }
            correctedRatioSummary.record(
                if (documents.isEmpty()) 0.0 else personalizedDocIds.size.toDouble() / documents.size.toDouble()
            )

            val remaining = documents.filterNot { assignment.containsKey(it.id) }
            if (remaining.isNotEmpty()) {
                val graph = neighborBuilder.build(
                    workspaceId = workspaceId,
                    documents = remaining,
                    embeddings = embeddingByDocumentId,
                    topK = treeProperties.neighborTopK,
                    minSimilarity = treeProperties.neighborMinSimilarity,
                    normalize = treeProperties.neighborNormalize,
                    semanticWeight = treeProperties.fusionSemanticWeight,
                    lexicalWeight = treeProperties.fusionLexicalWeight,
                    lexicalGate = treeProperties.fusionLexicalGate
                )

                val clusters = treeClusterer.cluster(
                    documents = remaining,
                    graph = graph,
                    maxClusterSize = treeProperties.maxClusterSize
                )

                val rawLabelsByCluster = treeLabeler.labelClusters(
                    workspaceDocuments = documents,
                    clusters = clusters
                )
                val mergedLabelMap = treeLabeler.mergeSimilarLabels(rawLabelsByCluster.values)

                clusters.forEach { cluster ->
                    val rawLabel = rawLabelsByCluster[cluster.id] ?: "general"
                    val label = mergedLabelMap[rawLabel] ?: rawLabel
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
            movedRatioSummary.record(movedRatio)

            val churnCount = movedCount
            val churnRatio = if (assignment.isEmpty()) 0.0 else churnCount.toDouble() / assignment.size.toDouble()
            churnRatioSummary.record(churnRatio)

            val labels = assignment.values.toMutableSet().apply {
                addAll(lockedNodes.map { it.label })
            }
                .filter { it.isNotBlank() }
                .sorted()
            val lockedRootLabels = lockedNodes
                .filter { node ->
                    val parentLabel = lockedParentLabelByLeaf[node.label]
                    parentLabel.isNullOrBlank() || parentLabel == "AutoDoc"
                }
                .map { it.label }
                .toSet()

            val topLabelByLeaf = labels.associateWith { leafLabel ->
                val lockedParent = lockedParentLabelByLeaf[leafLabel]
                when {
                    !lockedParent.isNullOrBlank() && lockedParent != "AutoDoc" -> lockedParent
                    lockedRootLabels.contains(leafLabel) -> leafLabel
                    else -> treeLabeler.topLevelLabel(leafLabel)
                }
            }

            val lockedMembershipConflict = lockedLabelByDocument.any { (documentId, lockedLabel) ->
                assignment[documentId] != lockedLabel
            }
            val lockedParentConflict = lockedNodes.any { node ->
                val expectedParent = lockedParentLabelByLeaf[node.label].takeUnless { it.isNullOrBlank() } ?: "AutoDoc"
                val actualParent = if (lockedRootLabels.contains(node.label)) {
                    "AutoDoc"
                } else {
                    topLabelByLeaf[node.label] ?: "AutoDoc"
                }
                actualParent != expectedParent
            }
            val preservedLockedNodeCount = lockedNodes.count { node ->
                val expectedParent = lockedParentLabelByLeaf[node.label].takeUnless { it.isNullOrBlank() } ?: "AutoDoc"
                val actualParent = if (lockedRootLabels.contains(node.label)) {
                    "AutoDoc"
                } else {
                    topLabelByLeaf[node.label] ?: "AutoDoc"
                }
                val parentOk = actualParent == expectedParent
                val docsOk = lockedDocsByLabel[node.label].orEmpty().all { documentId ->
                    assignment[documentId] == node.label
                }
                parentOk && docsOk
            }
            if (preservedLockedNodeCount > 0) {
                lockedNodePreservedCounter.increment(preservedLockedNodeCount.toDouble())
            }
            val lockConflict = lockedNodes.isNotEmpty() && (
                lockedMembershipConflict ||
                    lockedParentConflict ||
                    preservedLockedNodeCount < lockedNodes.size
                )

            val nodeRenameCount = TreeSnapshotMetrics.computeNodeRenameCount(
                activeNodes = activeNodes,
                newLabels = assignment.values.toSet()
            )
            val nextStatus = when {
                active == null -> "ACTIVE"
                lockConflict -> "RECOMMENDED"
                manual || movedRatio <= 0.35 -> "ACTIVE"
                else -> "RECOMMENDED"
            }

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

            val topNodes = mutableMapOf<String, TreeNodeRow>()
            topLabelByLeaf
                .filterKeys { !lockedRootLabels.contains(it) }
                .values
                .map { if (it.isBlank()) "general" else it.take(32) }
                .toSet()
                .sorted()
                .forEach { topLabel ->
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
                val topLabel = (topLabelByLeaf[label] ?: treeLabeler.topLevelLabel(label)).ifBlank { "general" }.take(32)
                val lockedRoot = locked && lockedRootLabels.contains(label)
                val parent = if (lockedRoot) null else topNodes[topLabel]
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
                    "similar_docs" to findSimilarDocs(doc.id, embeddingByDocumentId, documentsById, 3),
                    "signals" to buildSignals(
                        wasLocked = lockedLabelByDocument.containsKey(doc.id),
                        personalized = personalizedDocIds.contains(doc.id),
                        ruled = ruledDocIds.contains(doc.id)
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
                        "churn_count" to churnCount,
                        "lock_conflict" to lockConflict
                    )
                )
            }

            return snapshot
        } finally {
            rebuildDurationSummary.record((System.nanoTime() - startedAt).toDouble() / 1_000_000.0)
        }
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

    fun debugNeighbors(workspaceId: String, documentId: String): Map<String, Any?> {
        val documents = documentRepository.listWorkspaceDocuments(workspaceId)
        val source = documents.firstOrNull { it.id == documentId } ?: throw NotFoundException()
        val documentsById = documents.associateBy { it.id }
        val embeddings = embeddingRepository
            .listDocEmbeddings(workspaceId, embeddingProvider.modelVersion())
            .associateBy { it.documentId }
        if (documents.size <= 1) {
            return mapOf(
                "document_id" to source.id,
                "title" to source.title,
                "neighbors" to emptyList<Map<String, Any?>>()
            )
        }
        val graph = neighborBuilder.build(
            workspaceId = workspaceId,
            documents = documents,
            embeddings = embeddings,
            topK = maxOf(treeProperties.neighborTopK, 8),
            minSimilarity = 0.0,
            normalize = treeProperties.neighborNormalize,
            semanticWeight = treeProperties.fusionSemanticWeight,
            lexicalWeight = treeProperties.fusionLexicalWeight,
            lexicalGate = treeProperties.fusionLexicalGate
        )
        val neighbors = graph.adjacency[documentId].orEmpty().map { link ->
            mapOf(
                "neighbor_doc_id" to link.documentId,
                "title" to (documentsById[link.documentId]?.title ?: link.documentId),
                "sem_sim" to link.semanticSimilarity,
                "lex_sim" to link.lexicalSimilarity,
                "entity_overlap" to link.sharedEntityCount,
                "final_sim" to link.similarity,
                "gate_flags" to mapOf(
                    "lexical_gate_passed" to link.lexicalGatePassed,
                    "reason" to link.reason
                )
            )
        }
        return mapOf(
            "document_id" to source.id,
            "title" to source.title,
            "neighbors" to neighbors
        )
    }

    private fun buildSignals(wasLocked: Boolean, personalized: Boolean, ruled: Boolean): List<String> {
        val signals = mutableListOf<String>()
        if (wasLocked) signals += "LOCKED_NODE"
        if (personalized) signals += "PERSONALIZED_MOVE_SIGNAL"
        if (ruled) signals += "USER_RULE_MATCHED"
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

    private fun findSimilarDocs(
        documentId: String,
        embeddings: Map<String, EmbeddingRow>,
        documentsById: Map<String, DocumentRow>,
        limit: Int
    ): List<Map<String, Any?>> {
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
                    "title" to (documentsById[candidate.documentId]?.title ?: candidate.documentId),
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
        return dot / (sqrt(an) * sqrt(bn))
    }

    private fun emptyPersonalizationModel(): PersonalizationModel {
        return PersonalizationModel(
            docLabelScores = emptyMap(),
            keywordLabelScores = emptyMap(),
            entityLabelScores = emptyMap(),
            minScore = Double.MAX_VALUE
        )
    }

    private fun resolveUserRules(workspaceId: String, activeNodes: List<TreeNodeRow>): List<ResolvedUserRule> {
        return userRuleRepository
            .listByWorkspace(workspaceId)
            .mapNotNull { row ->
                val label = activeNodes.firstOrNull { it.id == row.nodeId }?.label
                    ?: treeRepository.findNodeByWorkspace(workspaceId, row.nodeId)?.label
                    ?: return@mapNotNull null
                val value = userRuleMatcher.normalizeRuleValue(row.ruleValue)
                if (value.isBlank()) {
                    return@mapNotNull null
                }
                ResolvedUserRule(
                    id = row.id,
                    ruleType = row.ruleType.uppercase(),
                    ruleValue = value,
                    targetLabel = label
                )
            }
            .distinctBy { "${it.ruleType}::${it.ruleValue}::${it.targetLabel}" }
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
        val currentMembership = treeRepository.findMembershipByWorkspaceAndDocument(context.workspaceId, documentId)
        if (currentMembership != null) {
            val currentNode = treeRepository.findNodeByWorkspace(context.workspaceId, currentMembership.nodeId)
            if (currentNode?.locked == true && currentMembership.nodeId != toNode.id) {
                throw BadRequestException("locked node membership is protected")
            }
        }
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
        if (node.locked) {
            throw BadRequestException("locked node label cannot be renamed")
        }
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
    private val auditService: AuditService,
    private val treeService: TreeService,
    private val treeRepository: TreeRepository,
    private val userRuleRepository: UserRuleRepository,
    private val featureFlags: FeatureFlags,
    private val meterRegistry: MeterRegistry
) {
    private val debugNeighborsCalledCounter = meterRegistry.counter("debug_neighbors_called_total")

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

    fun debugNeighbors(context: WorkspaceContext, documentId: String): Map<String, Any?> {
        requireOwner(context)
        if (!featureFlags.adminTreeDebug) {
            throw ForbiddenException("tree debug feature is disabled")
        }
        debugNeighborsCalledCounter.increment()
        return treeService.debugNeighbors(context.workspaceId, documentId)
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
            "moved_ratio" to summaryAverage("moved_ratio"),
            "churn_ratio" to summaryAverage("churn_ratio")
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
    fun createUserRule(context: WorkspaceContext, ruleType: String, ruleValue: String, nodeId: String): Map<String, Any?> {
        requireOwner(context)
        if (!featureFlags.userRulesV1) {
            throw ForbiddenException("user rules feature is disabled")
        }
        val normalizedType = ruleType.trim().uppercase()
        if (normalizedType !in setOf("ENTITY_CONTAINS", "TITLE_CONTAINS")) {
            throw BadRequestException("unsupported rule_type")
        }
        val normalizedValue = ruleValue.trim()
        if (normalizedValue.isBlank()) {
            throw BadRequestException("rule_value must not be blank")
        }
        val node = treeRepository.findNodeByWorkspace(context.workspaceId, nodeId) ?: throw NotFoundException()
        val rule = userRuleRepository.create(
            workspaceId = context.workspaceId,
            ruleType = normalizedType,
            ruleValue = normalizedValue.take(255),
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
                "node_id" to node.id
            )
        )
        return mapOf(
            "id" to rule.id,
            "rule_type" to rule.ruleType,
            "rule_value" to rule.ruleValue,
            "node_id" to rule.nodeId
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
