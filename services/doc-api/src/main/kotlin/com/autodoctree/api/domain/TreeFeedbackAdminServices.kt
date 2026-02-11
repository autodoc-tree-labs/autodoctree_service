package com.autodoctree.api.domain

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.AttachmentRepository
import com.autodoctree.api.db.AuditLogRepository
import com.autodoctree.api.db.ConceptPrototypeRepository
import com.autodoctree.api.db.ConceptPrototypeRow
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.DocumentSectionRepository
import com.autodoctree.api.db.EmbeddingRow
import com.autodoctree.api.db.EmbeddingRepository
import com.autodoctree.api.db.FeedbackEventRow
import com.autodoctree.api.db.FeedbackRepository
import com.autodoctree.api.db.OutboxRepository
import com.autodoctree.api.db.StageExecutionRepository
import com.autodoctree.api.db.TreeMembershipRow
import com.autodoctree.api.db.TreeNodeRow
import com.autodoctree.api.db.WorkspaceTreePolicyRepository
import com.autodoctree.api.db.TreeRepository
import com.autodoctree.api.db.TreeSnapshotRow
import com.autodoctree.api.db.UserRuleRepository
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

@Service
class TreeService(
    private val documentRepository: DocumentRepository,
    private val attachmentRepository: AttachmentRepository,
    private val documentSectionRepository: DocumentSectionRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val treeRepository: TreeRepository,
    private val conceptPrototypeRepository: ConceptPrototypeRepository,
    private val feedbackRepository: FeedbackRepository,
    private val userRuleRepository: UserRuleRepository,
    private val workspaceTreePolicyRepository: WorkspaceTreePolicyRepository,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val treeProperties: TreeProperties,
    private val featureFlags: FeatureFlags,
    private val neighborBuilder: NeighborBuilder,
    private val treeClusterer: TreeClusterer,
    private val structureWorkerClient: StructureWorkerClient,
    private val treeLabeler: TreeLabeler,
    private val labelerChain: LabelerChain,
    private val llmExplainGenerator: LlmExplainGenerator,
    private val treePersonalizationEngine: TreePersonalizationEngine,
    private val userRuleMatcher: UserRuleMatcher,
    private val embeddingProvider: EmbeddingProvider,
    private val embeddingAggregationService: EmbeddingAggregationService,
    private val embeddingQualityScorer: EmbeddingQualityScorer,
    private val llmTextGenerator: LlmTextGenerator,
    private val rebuildDebounceQueue: RebuildDebounceQueue,
    private val treeTelemetry: TreeTelemetry,
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val meterRegistryRef = meterRegistry
    private val rebuildDurationSummary = meterRegistry.summary("tree_rebuild_duration_ms")
    private val feedbackAppliedCounter = meterRegistry.counter("feedback_applied_total")
    private val correctedRatioSummary = meterRegistry.summary("corrected_ratio")
    private val rulesAppliedCounter = meterRegistry.counter("rules_applied_total")
    private val ruleHitCounter = meterRegistry.counter("rule_hit_total")
    private val ruleConflictCounter = meterRegistry.counter("rule_conflict_total")
    private val lockedNodePreservedCounter = meterRegistry.counter("locked_node_preserved_total")
    private val movedRatioSummary = meterRegistry.summary("moved_ratio")
    private val churnRatioSummary = meterRegistry.summary("churn_ratio")
    private val hubDocCounter = meterRegistry.counter("hub_doc_total")
    private val unsortedLowConfidenceCounter = meterRegistry.counter("unsorted_reason_total", "reason", "LOW_CONFIDENCE")
    private val unsortedHubCounter = meterRegistry.counter("unsorted_reason_total", "reason", "HUB")
    private val unsortedConflictCounter = meterRegistry.counter("unsorted_reason_total", "reason", "CONFLICT")
    private val unsortedTemplateCounter = meterRegistry.counter("unsorted_reason_total", "reason", "TEMPLATE")
    private val policyDecisionAutoCounter = meterRegistry.counter("tree.assign_policy_total", "decision", "AUTO")
    private val policyDecisionRecommendCounter = meterRegistry.counter("tree.assign_policy_total", "decision", "RECOMMEND")
    private val policyDecisionUnsortedCounter = meterRegistry.counter("tree.assign_policy_total", "decision", "UNSORTED")
    private val autoRatioSummary = meterRegistry.summary("auto_ratio")
    private val recommendRatioSummary = meterRegistry.summary("recommend_ratio")
    private val explainShownCounter = meterRegistry.counter("explain_shown_total")
    private val explainAcceptCounter = meterRegistry.counter("explain_accept_total")
    private val workerFallbackCounter = meterRegistry.counter("worker_fallback_total")
    private val workerFallbackRateSummary = meterRegistry.summary("worker_fallback_rate")
    private val incrementalAssignRateSummary = meterRegistry.summary("incremental_assign_rate")
    private val conceptCountSummary = meterRegistry.summary("concept_count")
    private val conceptDriftSummary = meterRegistry.summary("concept_drift")
    private val optimizerIterationsSummary = meterRegistry.summary("optimizer_iterations")
    private val changeCostSummary = meterRegistry.summary("change_cost")
    private val objectiveScoreSummary = meterRegistry.summary("objective_score")
    private val treeViewLatencySummary = meterRegistry.summary("tree_view_latency_ms")
    private val unsortedReasonCodes = setOf("LOW_CONFIDENCE", "HUB", "CONFLICT", "TEMPLATE", "RECOMMEND")

    private data class AssignmentPolicy(
        val autoThreshold: Double,
        val recommendThreshold: Double,
        val quarantineEnabled: Boolean,
        val rerankerEnabled: Boolean,
        val structureWorkerEnabled: Boolean,
        val source: String
    )

    private data class AssignmentPolicyOutcome(
        val confidenceByDocument: Map<String, Double>,
        val decisionByDocument: Map<String, String>,
        val reasonByDocument: Map<String, String>
    )

    private data class ConceptCandidate(
        val label: String,
        val vector: List<Double>,
        val docCount: Int
    )

    private data class ConceptPreassignOutcome(
        val assignmentByDocument: Map<String, String>,
        val confidenceByDocument: Map<String, Double>,
        val sourceByDocument: Map<String, String>,
        val activeConceptCount: Int
    )

    private data class ObjectiveBreakdown(
        val fitScore: Double,
        val changeCost: Double,
        val cannotViolations: Int,
        val sizePenalty: Double,
        val objectiveScore: Double
    )

    private data class OptimizerOutcome(
        val assignmentByDocument: Map<String, String>,
        val optimizedDocIds: Set<String>,
        val iterations: Int,
        val before: ObjectiveBreakdown,
        val after: ObjectiveBreakdown
    )

    private data class ViewProjectionOutcome(
        val assignmentByDocument: Map<String, String>,
        val metadataByDocument: Map<String, String>,
        val transformedDocCount: Int
    )

    @Transactional
    fun rebuildWorkspace(
        workspaceId: String,
        actorUserId: String? = null,
        manual: Boolean = false,
        viewType: TreeViewType = TreeViewType.TOPIC
    ): TreeSnapshotRow {
        val startedAt = System.nanoTime()
        val traceState = ensureTraceContext()
        val trace = treeTelemetry.begin(workspaceId)
        try {
            val normalizedView = resolveViewType(viewType)
            val ingestStartedAt = System.nanoTime()
            val documents = documentRepository.listWorkspaceDocuments(workspaceId)
            val documentsById = documents.associateBy { it.id }
            val active = treeRepository.findActiveSnapshot(workspaceId, normalizedView.name)
            val activeNodes = active?.let { treeRepository.listNodes(workspaceId, it.id, normalizedView.name) } ?: emptyList()
            val activeMemberships = active?.let { treeRepository.listMemberships(workspaceId, it.id, normalizedView.name) } ?: emptyList()
            val previousLabelCache = parseLabelCache(active?.labelCacheJson)

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

            treeTelemetry.recordStage(
                trace = trace,
                stage = "ingest",
                startedAtNanos = ingestStartedAt,
                details = mapOf(
                    "document_count" to documents.size,
                    "active_snapshot_id" to active?.id,
                    "locked_node_count" to lockedNodes.size,
                    "view_type" to normalizedView.apiValue
                )
            )

            val embedStartedAt = System.nanoTime()
            val embeddingByDocumentId = loadTreeEmbeddings(workspaceId, documents)
            treeTelemetry.recordStage(
                trace = trace,
                stage = "embed",
                startedAtNanos = embedStartedAt,
                details = mapOf(
                    "embedding_count" to embeddingByDocumentId.size,
                    "embedding_model" to embeddingProvider.modelVersion()
                )
            )

            val pairwiseStartedAt = System.nanoTime()
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
            val softRulePreferredLabelByDocument = mutableMapOf<String, String>()
            var ruleConflictCount = 0

            documents.forEach { doc ->
                val forced = lockedLabelByDocument[doc.id]
                if (forced != null) {
                    assignment[doc.id] = forced
                }
            }

            val ruleContextByDocument = if (featureFlags.userRulesV1) {
                buildRuleContextByDocument(workspaceId, documents)
            } else {
                emptyMap()
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
                        val context = ruleContextByDocument[doc.id] ?: UserRuleMatchContext()
                        val matchedRules = userRuleMatcher.matchAll(doc, rules, context)
                        if (matchedRules.isEmpty()) {
                            return@forEach
                        }
                        ruleHitCounter.increment()
                        val hardMatches = matchedRules.filter { it.ruleEffect == "HARD" }
                        if (hardMatches.isNotEmpty()) {
                            val hardTargets = hardMatches.map { it.targetLabel }.distinct()
                            if (hardTargets.size > 1) {
                                ruleConflictCounter.increment()
                                ruleConflictCount += 1
                            }
                            assignment[doc.id] = hardMatches.first().targetLabel
                            ruledDocIds += doc.id
                            rulesAppliedCounter.increment()
                            return@forEach
                        }
                        val preferredSoft = matchedRules
                            .filter { it.ruleEffect == "SOFT" }
                            .map { it.targetLabel }
                            .groupingBy { it }
                            .eachCount()
                            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                            ?.key
                        if (preferredSoft != null) {
                            softRulePreferredLabelByDocument[doc.id] = preferredSoft
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
            treeTelemetry.recordStage(
                trace = trace,
                stage = "pairwise",
                startedAtNanos = pairwiseStartedAt,
                details = mapOf(
                    "feedback_event_count" to feedbackEvents.size,
                    "personalized_doc_count" to personalizedDocIds.size,
                    "ruled_doc_count" to ruledDocIds.size,
                    "soft_rule_doc_count" to softRulePreferredLabelByDocument.size,
                    "rule_conflict_count" to ruleConflictCount
                )
            )

            var remaining = documents.filterNot { assignment.containsKey(it.id) }
            var graphStats = NeighborBuildStats()
            var labelSourceBreakdown = emptyMap<String, Int>()
            var labelCacheToPersist = previousLabelCache
            val assignmentPolicy = resolveAssignmentPolicy(workspaceId)
            var policyOutcome = AssignmentPolicyOutcome(
                confidenceByDocument = emptyMap(),
                decisionByDocument = emptyMap(),
                reasonByDocument = emptyMap()
            )
            val activeConcepts = if (treeProperties.conceptEnabled) {
                conceptPrototypeRepository.listByWorkspaceAndActiveSnapshot(workspaceId, normalizedView.name)
                    .mapNotNull { concept ->
                        parseVector(concept.prototypeVectorJson).takeIf { it.isNotEmpty() }?.let { vector ->
                            ConceptCandidate(
                                label = concept.label,
                                vector = vector,
                                docCount = concept.docCount
                            )
                        }
                    }
            } else {
                emptyList()
            }
            val conceptStartedAt = System.nanoTime()
            val conceptPreassignOutcome = if (treeProperties.conceptEnabled && remaining.isNotEmpty() && activeConcepts.isNotEmpty()) {
                preassignByConcept(
                    documents = remaining,
                    embeddings = embeddingByDocumentId,
                    activeConcepts = activeConcepts
                )
            } else {
                ConceptPreassignOutcome(
                    assignmentByDocument = emptyMap(),
                    confidenceByDocument = emptyMap(),
                    sourceByDocument = emptyMap(),
                    activeConceptCount = activeConcepts.size
                )
            }
            if (conceptPreassignOutcome.assignmentByDocument.isNotEmpty()) {
                assignment.putAll(conceptPreassignOutcome.assignmentByDocument)
            }
            val conceptAssignedDocIds = conceptPreassignOutcome.assignmentByDocument.keys.toSet()
            val conceptSourceByDocument = conceptPreassignOutcome.sourceByDocument
            val conceptAvgConfidence = conceptPreassignOutcome.confidenceByDocument.values
                .average()
                .takeIf { !it.isNaN() } ?: 0.0
            val candidateDocCount = remaining.size.coerceAtLeast(1)
            val incrementalAssignRate = conceptAssignedDocIds.size.toDouble() / candidateDocCount.toDouble()
            if (treeProperties.conceptEnabled) {
                incrementalAssignRateSummary.record(incrementalAssignRate)
                conceptCountSummary.record(activeConcepts.size.toDouble())
            }
            treeTelemetry.recordStage(
                trace = trace,
                stage = "concept_preassign",
                startedAtNanos = conceptStartedAt,
                details = mapOf(
                    "enabled" to treeProperties.conceptEnabled,
                    "active_concept_count" to conceptPreassignOutcome.activeConceptCount,
                    "candidate_doc_count" to remaining.size,
                    "assigned_doc_count" to conceptAssignedDocIds.size,
                    "incremental_assign_rate" to String.format("%.3f", incrementalAssignRate),
                    "avg_confidence" to String.format("%.3f", conceptAvgConfidence)
                )
            )
            remaining = documents.filterNot { assignment.containsKey(it.id) }
            val embeddingAvailableDocRatio = if (remaining.isEmpty()) {
                1.0
            } else {
                remaining.count { embeddingByDocumentId.containsKey(it.id) }.toDouble() / remaining.size.toDouble()
            }

            val graphStartedAt = System.nanoTime()
            var graph = NeighborGraph(emptyMap())
            var clusters: List<TreeCluster> = emptyList()
            val rawLabelsByCluster = mutableMapOf<String, String>()
            var mergedLabelMap = emptyMap<String, String>()
            val quarantineReasonByDocument = mutableMapOf<String, String>()
            if (remaining.isNotEmpty()) {
                val rerankerTextByDocument = if (assignmentPolicy.rerankerEnabled) {
                    buildRerankerTextByDocument(workspaceId, remaining)
                } else {
                    emptyMap()
                }
                graph = neighborBuilder.build(
                    workspaceId = workspaceId,
                    documents = remaining,
                    embeddings = embeddingByDocumentId,
                    topK = treeProperties.neighborTopK,
                    minSimilarity = treeProperties.neighborMinSimilarity,
                    normalize = treeProperties.neighborNormalize,
                    semanticWeight = treeProperties.fusionSemanticWeight,
                    lexicalWeight = treeProperties.fusionLexicalWeight,
                    lexicalGate = treeProperties.fusionLexicalGate,
                    mutualKnnRequired = treeProperties.neighborMutualKnnRequired,
                    snnThreshold = treeProperties.neighborSnnThreshold,
                    edgeBudget = treeProperties.neighborEdgeBudget,
                    rerankerEnabled = assignmentPolicy.rerankerEnabled,
                    rerankerPerDocBudget = treeProperties.rerankerPerDocBudget,
                    rerankerPassThreshold = treeProperties.rerankerPassThreshold,
                    rerankerTextByDocumentId = rerankerTextByDocument
                )

                graphStats = graph.stats
                treeTelemetry.recordGraphDistribution(graph)
                treeTelemetry.recordStage(
                    trace = trace,
                    stage = "graph",
                    startedAtNanos = graphStartedAt,
                    details = mapOf(
                        "edge_count" to graphStats.edgeCount,
                        "filtered_edge_count" to graphStats.filteredEdgeCount,
                        "avg_similarity" to String.format("%.3f", graphStats.averageSimilarity),
                        "mutual_pass_rate" to String.format("%.3f", graphStats.mutualPassRate),
                        "snn_pass_rate" to String.format("%.3f", graphStats.snnPassRate),
                        "hub_doc_count" to graphStats.hubDocCount,
                        "reranker_validated_pairs" to graphStats.rerankerValidatedPairs,
                        "reranker_pass_rate" to String.format("%.3f", graphStats.rerankerPassRate),
                        "reranker_fallback_rate" to String.format("%.3f", graphStats.rerankerFallbackRate)
                    )
                )

                val clusterStartedAt = System.nanoTime()
                var clusterSource = "LOCAL_CLUSTERER"
                var workerFallbackRate = 0.0
                clusters = if (assignmentPolicy.structureWorkerEnabled) {
                    runCatching {
                        val imported = importWorkerClusters(workspaceId, remaining, graph)
                        if (imported.isEmpty()) {
                            throw IllegalStateException("structure worker returned empty clusters")
                        }
                        clusterSource = "STRUCTURE_WORKER"
                        imported
                    }.getOrElse { ex ->
                        workerFallbackCounter.increment()
                        workerFallbackRate = 1.0
                        logger.warn(
                            "structure_worker_fallback workspace_id={} reason={}",
                            workspaceId,
                            ex.message
                        )
                        treeClusterer.cluster(
                            documents = remaining,
                            graph = graph,
                            maxClusterSize = treeProperties.maxClusterSize
                        )
                    }
                } else {
                    treeClusterer.cluster(
                        documents = remaining,
                        graph = graph,
                        maxClusterSize = treeProperties.maxClusterSize
                    )
                }
                if (assignmentPolicy.structureWorkerEnabled) {
                    workerFallbackRateSummary.record(workerFallbackRate)
                }
                val labelingResult = labelerChain.labelClusters(
                    workspaceDocuments = documents,
                    clusters = clusters,
                    existingCache = previousLabelCache
                )
                rawLabelsByCluster.putAll(labelingResult.labelsByCluster)
                mergedLabelMap = treeLabeler.mergeSimilarLabels(rawLabelsByCluster.values)
                labelSourceBreakdown = labelingResult.sourceBreakdown
                labelCacheToPersist = labelingResult.labelCacheBySignature
                treeTelemetry.recordClusterDistribution(clusters)
                treeTelemetry.recordStage(
                    trace = trace,
                    stage = "cluster",
                    startedAtNanos = clusterStartedAt,
                    details = mapOf(
                        "cluster_count" to clusters.size,
                        "avg_cluster_size" to String.format(
                            "%.3f",
                            clusters.map { it.documentIds.size }.average().takeIf { !it.isNaN() } ?: 0.0
                        ),
                        "label_source_breakdown" to labelSourceBreakdown,
                        "source" to clusterSource,
                        "worker_fallback_rate" to String.format("%.3f", workerFallbackRate)
                    )
                )

                clusters.forEach { cluster ->
                    val rawLabel = rawLabelsByCluster[cluster.id] ?: "general"
                    val label = mergedLabelMap[rawLabel] ?: rawLabel
                    cluster.documentIds.forEach { docId ->
                        assignment[docId] = label
                    }
                }
                val softRuledDocIds = applySoftRules(
                    documents = remaining,
                    adjacency = graph.adjacency,
                    assignment = assignment,
                    preferredLabels = softRulePreferredLabelByDocument
                )
                if (softRuledDocIds.isNotEmpty()) {
                    ruledDocIds += softRuledDocIds
                    rulesAppliedCounter.increment(softRuledDocIds.size.toDouble())
                }
                if (assignmentPolicy.quarantineEnabled) {
                    quarantineReasonByDocument.putAll(
                        applyQuarantinePolicies(
                            workspaceId = workspaceId,
                            documents = remaining,
                            adjacency = graph.adjacency,
                            assignment = assignment
                        )
                    )
                }
                policyOutcome = applyAssignmentPolicy(
                    workspaceId = workspaceId,
                    documents = remaining,
                    adjacency = graph.adjacency,
                    assignment = assignment,
                    existingReasons = quarantineReasonByDocument,
                    feedbackEvents = feedbackEvents,
                    policy = assignmentPolicy
                )
                quarantineReasonByDocument.putAll(policyOutcome.reasonByDocument)
            } else {
                treeTelemetry.recordStage(
                    trace = trace,
                    stage = "graph",
                    startedAtNanos = graphStartedAt,
                    details = mapOf("skipped" to true, "reason" to "no_remaining_docs")
                )
                treeTelemetry.recordStage(
                    trace = trace,
                    stage = "cluster",
                    startedAtNanos = System.nanoTime(),
                    details = mapOf("skipped" to true, "reason" to "no_remaining_docs")
                )
            }

            val assignStartedAt = System.nanoTime()
            documents.forEach { doc ->
                assignment.putIfAbsent(doc.id, "general")
            }
            val viewProjection = applyViewProjection(
                viewType = normalizedView,
                documents = documents,
                assignment = assignment.toMap(),
                frozenDocIds = lockedLabelByDocument.keys + quarantineReasonByDocument.keys
            )
            if (viewProjection.transformedDocCount > 0) {
                assignment.putAll(viewProjection.assignmentByDocument)
            }
            val viewMetadataByDocument = viewProjection.metadataByDocument

            val previousDocToLabel = activeMemberships.associate { membership ->
                val node = activeNodes.firstOrNull { it.id == membership.nodeId }
                membership.documentId to (node?.label ?: "")
            }

            val optimizerStartedAt = System.nanoTime()
            val frozenDocIds = lockedLabelByDocument.keys + quarantineReasonByDocument.keys
            val optimizerOutcome = if (treeProperties.optimizerEnabled) {
                optimizeAssignment(
                    documents = documents,
                    assignment = assignment.toMap(),
                    adjacency = graph.adjacency,
                    previousDocToLabel = previousDocToLabel,
                    lockedLabelByDocument = lockedLabelByDocument,
                    frozenDocIds = frozenDocIds
                )
            } else {
                null
            }
            if (optimizerOutcome != null) {
                assignment.clear()
                assignment.putAll(optimizerOutcome.assignmentByDocument)
                optimizerIterationsSummary.record(optimizerOutcome.iterations.toDouble())
                changeCostSummary.record(optimizerOutcome.after.changeCost)
                objectiveScoreSummary.record(optimizerOutcome.after.objectiveScore)
            }
            treeTelemetry.recordStage(
                trace = trace,
                stage = "optimizer",
                startedAtNanos = optimizerStartedAt,
                details = if (optimizerOutcome == null) {
                    mapOf("enabled" to treeProperties.optimizerEnabled, "skipped" to true)
                } else {
                    mapOf(
                        "enabled" to true,
                        "iterations" to optimizerOutcome.iterations,
                        "optimized_doc_count" to optimizerOutcome.optimizedDocIds.size,
                        "objective_before" to String.format("%.3f", optimizerOutcome.before.objectiveScore),
                        "objective_after" to String.format("%.3f", optimizerOutcome.after.objectiveScore),
                        "change_cost" to String.format("%.3f", optimizerOutcome.after.changeCost),
                        "cannot_violations" to optimizerOutcome.after.cannotViolations,
                        "size_penalty" to String.format("%.3f", optimizerOutcome.after.sizePenalty)
                    )
                }
            )

            val movedCount = assignment.entries.count { (docId, newLabel) ->
                previousDocToLabel[docId] != null && previousDocToLabel[docId] != newLabel
            }
            val movedRatio = if (assignment.isEmpty()) 0.0 else movedCount.toDouble() / assignment.size.toDouble()
            movedRatioSummary.record(movedRatio)

            val churnCount = movedCount
            val churnRatio = if (assignment.isEmpty()) 0.0 else churnCount.toDouble() / assignment.size.toDouble()
            churnRatioSummary.record(churnRatio)
            val unsortedCount = assignment.values.count { isUnsortedLabel(it) }
            val unsortedRatio = if (assignment.isEmpty()) 0.0 else unsortedCount.toDouble() / assignment.size.toDouble()
            val decisionCounts = policyOutcome.decisionByDocument.values.groupingBy { it }.eachCount()
            val policyDocCount = policyOutcome.decisionByDocument.size
            val autoCount = decisionCounts["AUTO"] ?: 0
            val recommendCount = decisionCounts["RECOMMEND"] ?: 0
            val policyUnsortedCount = decisionCounts["UNSORTED"] ?: 0
            val autoRatio = if (policyDocCount == 0) 0.0 else autoCount.toDouble() / policyDocCount.toDouble()
            val recommendRatio = if (policyDocCount == 0) 0.0 else recommendCount.toDouble() / policyDocCount.toDouble()
            autoRatioSummary.record(autoRatio)
            recommendRatioSummary.record(recommendRatio)
            treeTelemetry.recordUnsortedRatio(unsortedRatio)
            treeTelemetry.recordStage(
                trace = trace,
                stage = "assign",
                startedAtNanos = assignStartedAt,
                details = mapOf(
                    "assigned_count" to assignment.size,
                    "moved_count" to movedCount,
                    "moved_ratio" to String.format("%.3f", movedRatio),
                    "unsorted_count" to unsortedCount,
                    "unsorted_ratio" to String.format("%.3f", unsortedRatio),
                    "auto_ratio" to String.format("%.3f", autoRatio),
                    "recommend_ratio" to String.format("%.3f", recommendRatio),
                    "policy_decision_count" to mapOf(
                        "AUTO" to autoCount,
                        "RECOMMEND" to recommendCount,
                        "UNSORTED" to policyUnsortedCount
                    ),
                    "policy_threshold" to mapOf(
                        "auto" to assignmentPolicy.autoThreshold,
                        "recommend" to assignmentPolicy.recommendThreshold,
                        "quarantine_enabled" to assignmentPolicy.quarantineEnabled,
                        "reranker_enabled" to assignmentPolicy.rerankerEnabled,
                        "structure_worker_enabled" to assignmentPolicy.structureWorkerEnabled,
                        "source" to assignmentPolicy.source
                    ),
                    "concept_preassigned_count" to conceptAssignedDocIds.size,
                    "optimizer_enabled" to treeProperties.optimizerEnabled,
                    "optimizer_iterations" to (optimizerOutcome?.iterations ?: 0),
                    "objective_score" to String.format(
                        "%.3f",
                        optimizerOutcome?.after?.objectiveScore ?: 0.0
                    ),
                    "view_type" to normalizedView.apiValue,
                    "view_transformed_doc_count" to viewProjection.transformedDocCount,
                    "unsorted_reason_breakdown" to quarantineReasonByDocument.values.groupingBy { it }.eachCount()
                )
            )

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
                treeRepository.markAllSnapshotsRecommended(workspaceId, normalizedView.name)
            }

            val treeExtractStartedAt = System.nanoTime()
            val snapshot = treeRepository.createSnapshot(
                workspaceId = workspaceId,
                viewType = normalizedView.name,
                status = nextStatus,
                movedRatio = movedRatio,
                churnCount = churnCount,
                nodeRenameCount = nodeRenameCount,
                labelCacheJson = objectMapper.writeValueAsString(labelCacheToPersist)
            )

            val root = treeRepository.insertNode(
                workspaceId = workspaceId,
                snapshotId = snapshot.id,
                viewType = normalizedView.name,
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
                        viewType = normalizedView.name,
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
                    viewType = normalizedView.name,
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
                val keywords = treeLabeler.keywords(doc.title + " " + (doc.bodyText ?: ""), 5)
                val similarDocs = findSimilarDocs(doc.id, embeddingByDocumentId, documentsById, 3)
                val evidence = buildExplainEvidence(
                    graph.adjacency[doc.id].orEmpty(),
                    documentsById,
                    limit = 3
                )
                val signals = buildSignals(
                    wasLocked = lockedLabelByDocument.containsKey(doc.id),
                    personalized = personalizedDocIds.contains(doc.id),
                    ruled = ruledDocIds.contains(doc.id),
                    conceptPreassigned = conceptAssignedDocIds.contains(doc.id),
                    conceptSource = conceptSourceByDocument[doc.id],
                    optimizerAdjusted = optimizerOutcome?.optimizedDocIds?.contains(doc.id) == true,
                    viewSignal = viewMetadataByDocument[doc.id],
                    quarantineReason = quarantineReasonByDocument[doc.id]
                )
                val llmSentence = llmExplainGenerator.generate(
                    keywords = keywords,
                    similarDocs = similarDocs,
                    signals = signals
                ) ?: fallbackExplainSentence(keywords, signals, evidence)
                val rationale = mapOf(
                    "keywords" to keywords,
                    "similar_docs" to similarDocs,
                    "signals" to signals,
                    "evidence" to evidence,
                    "llm_sentence" to llmSentence
                )
                treeRepository.insertMembership(
                    workspaceId = workspaceId,
                    snapshotId = snapshot.id,
                    viewType = normalizedView.name,
                    nodeId = node.id,
                    documentId = doc.id,
                    rationaleJson = objectMapper.writeValueAsString(rationale)
                )
            }
            treeTelemetry.recordStage(
                trace = trace,
                stage = "tree_extract",
                startedAtNanos = treeExtractStartedAt,
                details = mapOf(
                    "snapshot_id" to snapshot.id,
                    "node_count" to (labelToNode.size + topNodes.size + 1),
                    "membership_count" to assignment.size
                )
            )

            var conceptDriftAverage = 0.0
            if (treeProperties.conceptEnabled) {
                val conceptUpdateStartedAt = System.nanoTime()
                val conceptRows = buildSnapshotConceptRows(
                    workspaceId = workspaceId,
                    snapshotId = snapshot.id,
                    assignment = assignment,
                    embeddings = embeddingByDocumentId,
                    previousConcepts = activeConcepts
                )
                conceptPrototypeRepository.replaceSnapshotConcepts(workspaceId, snapshot.id, conceptRows)
                conceptDriftAverage = conceptRows
                    .map { it.driftScore }
                    .average()
                    .takeIf { !it.isNaN() } ?: 0.0
                conceptCountSummary.record(conceptRows.size.toDouble())
                conceptDriftSummary.record(conceptDriftAverage)
                treeTelemetry.recordStage(
                    trace = trace,
                    stage = "concept_update",
                    startedAtNanos = conceptUpdateStartedAt,
                    details = mapOf(
                        "enabled" to true,
                        "concept_count" to conceptRows.size,
                        "concept_drift" to String.format("%.3f", conceptDriftAverage)
                    )
                )
            } else {
                treeTelemetry.recordStage(
                    trace = trace,
                    stage = "concept_update",
                    startedAtNanos = System.nanoTime(),
                    details = mapOf("enabled" to false, "skipped" to true)
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

            val rebuildDurationMs = treeTelemetry.complete(trace)
            val summaryPayload = treeTelemetry.buildSummaryPayload(
                workspaceId = workspaceId,
                snapshotId = snapshot.id,
                documentCount = documents.size,
                status = snapshot.status,
                movedRatio = movedRatio,
                churnRatio = churnRatio,
                unsortedRatio = unsortedRatio,
                graphStats = graphStats,
                stageLogs = trace.stageLogs
            ) + mapOf(
                "embedding_provider" to embeddingProvider.providerId(),
                "embedding_model" to embeddingProvider.modelVersion(),
                "llm_provider" to llmTextGenerator.providerId(),
                "llm_model" to llmTextGenerator.modelVersion(),
                "embedding_available_doc_ratio" to String.format("%.3f", embeddingAvailableDocRatio),
                "rebuild_duration_ms" to String.format("%.3f", rebuildDurationMs),
                "mutual_pass_rate" to String.format("%.3f", graphStats.mutualPassRate),
                "snn_pass_rate" to String.format("%.3f", graphStats.snnPassRate),
                "hub_doc_count" to graphStats.hubDocCount,
                "reranker_validated_pairs" to graphStats.rerankerValidatedPairs,
                "reranker_pass_rate" to String.format("%.3f", graphStats.rerankerPassRate),
                "reranker_fallback_rate" to String.format("%.3f", graphStats.rerankerFallbackRate),
                "auto_ratio" to String.format("%.3f", autoRatio),
                "recommend_ratio" to String.format("%.3f", recommendRatio),
                "incremental_assign_rate" to String.format("%.3f", incrementalAssignRate),
                "concept_count" to activeConcepts.size,
                "concept_preassigned_count" to conceptAssignedDocIds.size,
                "concept_drift" to String.format("%.3f", conceptDriftAverage),
                "optimizer_enabled" to treeProperties.optimizerEnabled,
                "optimizer_iterations" to (optimizerOutcome?.iterations ?: 0),
                "objective_score" to String.format("%.3f", optimizerOutcome?.after?.objectiveScore ?: 0.0),
                "change_cost" to String.format("%.3f", optimizerOutcome?.after?.changeCost ?: 0.0),
                "view_type" to normalizedView.apiValue,
                "view_transformed_doc_count" to viewProjection.transformedDocCount,
                "policy_threshold" to mapOf(
                    "auto" to assignmentPolicy.autoThreshold,
                    "recommend" to assignmentPolicy.recommendThreshold,
                    "quarantine_enabled" to assignmentPolicy.quarantineEnabled,
                    "reranker_enabled" to assignmentPolicy.rerankerEnabled,
                    "structure_worker_enabled" to assignmentPolicy.structureWorkerEnabled,
                    "source" to assignmentPolicy.source
                ),
                "similarity_source_breakdown" to mapOf(
                    "embedding_only" to graphStats.similaritySourceBreakdown.embeddingOnly,
                    "lexical_only" to graphStats.similaritySourceBreakdown.lexicalOnly,
                    "fused" to graphStats.similaritySourceBreakdown.fused
                ),
                "label_source_breakdown" to labelSourceBreakdown,
                "rule_conflict_count" to ruleConflictCount,
                "soft_rule_doc_count" to softRulePreferredLabelByDocument.size,
                "unsorted_reason_breakdown" to quarantineReasonByDocument.values.groupingBy { it }.eachCount()
            )
            treeTelemetry.logSummary(summaryPayload)
            treeTelemetry.storeDebugSnapshot(
                TreeRebuildDebugSnapshot(
                    workspaceId = workspaceId,
                    snapshotId = snapshot.id,
                    createdAt = snapshot.createdAt,
                    stageLogs = trace.stageLogs.toList(),
                    parameters = treeTelemetry.parameterSnapshot(treeProperties, featureFlags),
                    models = mapOf(
                        "embedding_provider" to embeddingProvider.providerId(),
                        "embedding_model" to embeddingProvider.modelVersion(),
                        "llm_provider" to llmTextGenerator.providerId(),
                        "llm_model" to llmTextGenerator.modelVersion()
                    ),
                    decisions = mapOf(
                        "status" to snapshot.status,
                        "moved_ratio" to movedRatio,
                        "churn_count" to churnCount,
                        "node_rename_count" to nodeRenameCount,
                        "lock_conflict" to lockConflict,
                        "unsorted_ratio" to unsortedRatio,
                        "embedding_available_doc_ratio" to embeddingAvailableDocRatio,
                        "hub_doc_count" to graphStats.hubDocCount,
                        "reranker_validated_pairs" to graphStats.rerankerValidatedPairs,
                        "reranker_pass_rate" to graphStats.rerankerPassRate,
                        "reranker_fallback_rate" to graphStats.rerankerFallbackRate,
                        "auto_ratio" to autoRatio,
                        "recommend_ratio" to recommendRatio,
                        "incremental_assign_rate" to incrementalAssignRate,
                        "concept_count" to activeConcepts.size,
                        "concept_preassigned_count" to conceptAssignedDocIds.size,
                        "concept_drift" to conceptDriftAverage,
                        "optimizer_enabled" to treeProperties.optimizerEnabled,
                        "optimizer_iterations" to (optimizerOutcome?.iterations ?: 0),
                        "fit_score" to (optimizerOutcome?.after?.fitScore ?: 0.0),
                        "objective_score" to (optimizerOutcome?.after?.objectiveScore ?: 0.0),
                        "change_cost" to (optimizerOutcome?.after?.changeCost ?: 0.0),
                        "cannot_violations" to (optimizerOutcome?.after?.cannotViolations ?: 0),
                        "size_penalty" to (optimizerOutcome?.after?.sizePenalty ?: 0.0),
                        "view_type" to normalizedView.apiValue,
                        "view_transformed_doc_count" to viewProjection.transformedDocCount,
                        "rule_conflict_count" to ruleConflictCount,
                        "soft_rule_doc_count" to softRulePreferredLabelByDocument.size,
                        "policy_threshold" to mapOf(
                            "auto" to assignmentPolicy.autoThreshold,
                            "recommend" to assignmentPolicy.recommendThreshold,
                            "quarantine_enabled" to assignmentPolicy.quarantineEnabled,
                            "reranker_enabled" to assignmentPolicy.rerankerEnabled,
                            "structure_worker_enabled" to assignmentPolicy.structureWorkerEnabled,
                            "source" to assignmentPolicy.source
                        ),
                        "unsorted_reason_breakdown" to quarantineReasonByDocument.values.groupingBy { it }.eachCount()
                    )
                )
            )

            return snapshot
        } finally {
            rebuildDurationSummary.record((System.nanoTime() - startedAt).toDouble() / 1_000_000.0)
            clearTraceContext(traceState)
        }
    }

    fun getActiveTree(context: WorkspaceContext, viewType: TreeViewType = TreeViewType.TOPIC): Map<String, Any?> {
        val normalizedView = resolveViewType(viewType)
        val active = treeRepository.findActiveSnapshot(context.workspaceId, normalizedView.name)
            ?: return mapOf(
                "snapshot_id" to null,
                "status" to "EMPTY",
                "view_type" to normalizedView.apiValue,
                "nodes" to emptyList<Any>()
            )
        val nodes = treeRepository.listNodes(context.workspaceId, active.id, normalizedView.name)
        val memberships = treeRepository.listMemberships(context.workspaceId, active.id, normalizedView.name)
        val documentsById = documentRepository.listWorkspaceDocuments(context.workspaceId).associateBy { it.id }
        val membershipsByNode = memberships.groupBy { it.nodeId }
        val membershipByDocumentId = memberships.associateBy { it.documentId }
        val nodeById = nodes.associateBy { it.id }
        val nodeDocumentCount = membershipsByNode.mapValues { it.value.size }
        return mapOf(
            "snapshot_id" to active.id,
            "status" to active.status,
            "view_type" to normalizedView.apiValue,
            "nodes" to nodes.map {
                val nodeMemberships = membershipsByNode[it.id].orEmpty()
                val nodeDocumentIds = nodeMemberships.map(TreeMembershipRow::documentId)
                mapOf(
                    "id" to it.id,
                    "parent_id" to it.parentId,
                    "label" to it.label,
                    "locked" to it.locked,
                    "documents" to nodeDocumentIds,
                    "document_summaries" to nodeMemberships.map { membership ->
                        val rationale = parseRationale(membership.rationaleJson)
                        mapOf(
                            "id" to membership.documentId,
                            "title" to (documentsById[membership.documentId]?.title ?: membership.documentId),
                            "quarantine_reason" to resolveQuarantineReason(rationale),
                            "placement_confidence" to resolvePlacementConfidence(rationale),
                            "placement_candidates" to buildPlacementCandidates(
                                rationale = rationale,
                                membershipByDocumentId = membershipByDocumentId,
                                nodeById = nodeById,
                                nodeDocumentCount = nodeDocumentCount,
                                currentNodeId = membership.nodeId
                            )
                        )
                    }
                )
            }
        )
    }

    @Transactional
    fun getTreeByView(context: WorkspaceContext, viewType: TreeViewType): Map<String, Any?> {
        val startedAt = System.nanoTime()
        val normalizedView = resolveViewType(viewType)
        val existing = treeRepository.findActiveSnapshot(context.workspaceId, normalizedView.name)
        var generated = false
        if (existing == null) {
            rebuildWorkspace(
                workspaceId = context.workspaceId,
                actorUserId = context.userId,
                manual = true,
                viewType = normalizedView
            )
            generated = true
        }
        meterRegistryRef.counter("tree_view_request_total", "view", normalizedView.apiValue).increment()
        treeViewLatencySummary.record((System.nanoTime() - startedAt).toDouble() / 1_000_000.0)
        logger.info(
            "tree_view_resolved workspace_id={} view_type={} generated={}",
            context.workspaceId,
            normalizedView.apiValue,
            generated
        )
        return getActiveTree(context, normalizedView)
    }

    fun listSnapshots(context: WorkspaceContext, viewType: TreeViewType = TreeViewType.TOPIC): Map<String, Any?> {
        val normalizedView = resolveViewType(viewType)
        return mapOf(
            "items" to treeRepository.listSnapshots(context.workspaceId, normalizedView.name).map {
                mapOf(
                    "id" to it.id,
                    "view_type" to TreeViewType.fromDb(it.viewType).apiValue,
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
    fun requestRebuild(context: WorkspaceContext, mode: String, viewType: TreeViewType = TreeViewType.TOPIC): Map<String, Any?> {
        val normalizedView = resolveViewType(viewType)
        requireEditor(context)
        val manual = mode.equals("IMMEDIATE", ignoreCase = true)
        if (!manual) {
            rebuildDebounceQueue.request(context.workspaceId, "MANUAL_DEBOUNCED_REQUEST")
            return mapOf(
                "snapshot_id" to null,
                "status" to "QUEUED",
                "view_type" to normalizedView.apiValue,
                "pending_count" to rebuildDebounceQueue.pendingCount(context.workspaceId)
            )
        }
        val snapshot = rebuildWorkspace(context.workspaceId, context.userId, manual = manual, viewType = normalizedView)
        return mapOf("snapshot_id" to snapshot.id, "status" to snapshot.status, "view_type" to normalizedView.apiValue)
    }

    @Transactional
    fun activateSnapshot(context: WorkspaceContext, snapshotId: String) {
        requireEditor(context)
        val snapshot = treeRepository.findSnapshotByWorkspace(context.workspaceId, snapshotId) ?: throw NotFoundException()
        treeRepository.activateSnapshot(context.workspaceId, snapshot.id, context.userId, snapshot.viewType)
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
            "signals" to emptyList<String>(),
            "evidence" to mapOf(
                "neighbors" to emptyList<Map<String, Any?>>(),
                "reason_codes" to emptyList<String>()
            ),
            "llm_sentence" to null
        )
        val rationale = (membership?.let { parseRationale(it.rationaleJson) } ?: fallback)
            .let { normalizeExplainRationale(it) }
        explainShownCounter.increment()
        return mapOf(
            "document_id" to documentId,
            "node_id" to membership?.nodeId,
            "rationale" to rationale
        )
    }

    @Transactional
    fun acceptExplain(context: WorkspaceContext, documentId: String) {
        val document = documentRepository.findByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        val membership = treeRepository.findMembershipByWorkspaceAndDocument(context.workspaceId, document.id)
        val rationale = membership?.let { parseRationale(it.rationaleJson) }.orEmpty()
        val reasonCodes = rationale["signals"].safeStringList()
        val payload = mapOf(
            "document_id" to document.id,
            "snapshot_id" to membership?.snapshotId,
            "node_id" to membership?.nodeId,
            "reason_codes" to reasonCodes,
            "accepted_at" to System.currentTimeMillis()
        )
        feedbackRepository.insert(
            context.workspaceId,
            context.userId,
            "EXPLAIN_ACCEPT",
            objectMapper.writeValueAsString(payload)
        )
        auditService.write(context.workspaceId, context.userId, "feedback.explain_accept", payload)
        explainAcceptCounter.increment()
    }

    fun debugNeighbors(workspaceId: String, documentId: String): Map<String, Any?> {
        val documents = documentRepository.listWorkspaceDocuments(workspaceId)
        val source = documents.firstOrNull { it.id == documentId } ?: throw NotFoundException()
        val documentsById = documents.associateBy { it.id }
        val embeddings = loadTreeEmbeddings(workspaceId, documents)
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
            lexicalGate = treeProperties.fusionLexicalGate,
            mutualKnnRequired = treeProperties.neighborMutualKnnRequired,
            snnThreshold = treeProperties.neighborSnnThreshold,
            edgeBudget = treeProperties.neighborEdgeBudget
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

    fun debugDocument(workspaceId: String, documentId: String, topN: Int): Map<String, Any?> {
        val documents = documentRepository.listWorkspaceDocuments(workspaceId)
        val source = documents.firstOrNull { it.id == documentId } ?: throw NotFoundException()
        val documentsById = documents.associateBy { it.id }
        val embeddings = loadTreeEmbeddings(workspaceId, documentRepository.listWorkspaceDocuments(workspaceId))
        val graph = neighborBuilder.build(
            workspaceId = workspaceId,
            documents = documents,
            embeddings = embeddings,
            topK = maxOf(treeProperties.neighborTopK, topN.coerceIn(1, 20)),
            minSimilarity = 0.0,
            normalize = treeProperties.neighborNormalize,
            semanticWeight = treeProperties.fusionSemanticWeight,
            lexicalWeight = treeProperties.fusionLexicalWeight,
            lexicalGate = treeProperties.fusionLexicalGate,
            mutualKnnRequired = treeProperties.neighborMutualKnnRequired,
            snnThreshold = treeProperties.neighborSnnThreshold,
            edgeBudget = treeProperties.neighborEdgeBudget
        )
        val selectedLinks = graph.adjacency[documentId].orEmpty().take(topN.coerceIn(1, 20))
        val assignmentMembership = treeRepository.findMembershipByWorkspaceAndDocument(workspaceId, documentId)
        val assignmentNode = assignmentMembership?.nodeId?.let { treeRepository.findNodeByWorkspace(workspaceId, it) }
        val assignmentSignals = assignmentMembership
            ?.let { parseRationale(it.rationaleJson)["signals"].safeStringList() }
            .orEmpty()
        val quarantineReason = assignmentSignals.firstOrNull { signal ->
            signal in setOf("LOW_CONFIDENCE", "HUB", "TEMPLATE", "CONFLICT")
        }
        val confidence = when {
            selectedLinks.isEmpty() -> 0.0
            selectedLinks.size == 1 -> selectedLinks.first().similarity.coerceIn(0.0, 1.0)
            else -> (selectedLinks[0].similarity - selectedLinks[1].similarity).coerceIn(0.0, 1.0)
        }
        return mapOf(
            "document_id" to source.id,
            "title_mask" to maskedText(source.title),
            "assignment" to mapOf(
                "node_id" to assignmentMembership?.nodeId,
                "node_label" to assignmentNode?.label,
                "snapshot_id" to assignmentMembership?.snapshotId,
                "quarantine_reason" to quarantineReason
            ),
            "assignment_confidence" to confidence,
            "neighbors" to selectedLinks.map { link ->
                mapOf(
                    "neighbor_doc_id" to link.documentId,
                    "title_mask" to maskedText(documentsById[link.documentId]?.title ?: link.documentId),
                    "channel_scores" to mapOf(
                        "semantic" to link.semanticSimilarity,
                        "lexical" to link.lexicalSimilarity,
                        "final" to link.similarity
                    ),
                    "edge_decision" to mapOf(
                        "lexical_gate_passed" to link.lexicalGatePassed,
                        "reason" to link.reason,
                        "entity_overlap" to link.sharedEntityCount,
                        "title_overlap" to link.titleOverlap
                    )
                )
            },
            "trace_id" to MDC.get("trace_id"),
            "request_id" to MDC.get("request_id")
        )
    }

    fun debugCluster(workspaceId: String, clusterId: String): Map<String, Any?> {
        val active = treeRepository.findActiveSnapshot(workspaceId) ?: throw NotFoundException()
        val clusterNode = treeRepository.findNodeByWorkspace(workspaceId, clusterId) ?: throw NotFoundException()
        if (clusterNode.snapshotId != active.id) {
            throw NotFoundException()
        }
        val memberships = treeRepository.listMemberships(workspaceId, active.id).filter { it.nodeId == clusterId }
        val documents = documentRepository.listWorkspaceDocuments(workspaceId)
        val documentsById = documents.associateBy { it.id }
        val embeddings = loadTreeEmbeddings(workspaceId, documents)
        val memberIds = memberships.map { it.documentId }
        val vectorsByDoc = memberIds.associateWith { memberId ->
            embeddings[memberId]?.let { embedding ->
                objectMapper.readValue(embedding.vectorJson, List::class.java).mapNotNull { value ->
                    (value as? Number)?.toDouble()
                }
            }.orEmpty()
        }
        val exemplars = memberIds.map { memberId ->
            val sourceVector = vectorsByDoc[memberId].orEmpty()
            val avgSimilarity = if (sourceVector.isEmpty()) {
                0.0
            } else {
                memberIds
                    .asSequence()
                    .filter { it != memberId }
                    .mapNotNull { otherId ->
                        val target = vectorsByDoc[otherId].orEmpty()
                        if (target.isEmpty()) {
                            null
                        } else {
                            cosine(sourceVector, target)
                        }
                    }
                    .average()
                    .takeIf { !it.isNaN() } ?: 0.0
            }
            mapOf(
                "document_id" to memberId,
                "title_mask" to maskedText(documentsById[memberId]?.title ?: memberId),
                "avg_similarity" to avgSimilarity
            )
        }.sortedByDescending { it["avg_similarity"] as Double }
            .take(3)

        val memberDocs = memberIds.mapNotNull { documentsById[it] }
        val labelCandidates = treeLabeler.keywords(
            memberDocs.joinToString(" ") { "${it.title} ${it.bodyText ?: ""}" },
            8
        )
        return mapOf(
            "cluster_id" to clusterNode.id,
            "snapshot_id" to active.id,
            "label" to clusterNode.label,
            "member_count" to memberships.size,
            "members" to memberships.map { membership ->
                mapOf(
                    "document_id" to membership.documentId,
                    "title_mask" to maskedText(documentsById[membership.documentId]?.title ?: membership.documentId),
                    "signals" to parseRationale(membership.rationaleJson)["signals"].safeStringList()
                )
            },
            "exemplars" to exemplars,
            "label_candidates" to labelCandidates,
            "trace_id" to MDC.get("trace_id"),
            "request_id" to MDC.get("request_id")
        )
    }

    fun debugRebuild(workspaceId: String, snapshotId: String): Map<String, Any?> {
        val snapshot = treeRepository.findSnapshotByWorkspace(workspaceId, snapshotId) ?: throw NotFoundException()
        val nodes = treeRepository.listNodes(workspaceId, snapshot.id, snapshot.viewType)
        val memberships = treeRepository.listMemberships(workspaceId, snapshot.id, snapshot.viewType)
        val nodeById = nodes.associateBy { it.id }
        val unsortedCount = memberships.count { membership ->
            isUnsortedLabel(nodeById[membership.nodeId]?.label ?: "")
        }
        val unsortedRatio = if (memberships.isEmpty()) 0.0 else unsortedCount.toDouble() / memberships.size.toDouble()
        val clusterCount = nodes.count { it.depth >= 2 }.takeIf { it > 0 }
            ?: nodes.count { it.depth == 1 && it.label != "AutoDoc" }

        val telemetrySnapshot = treeTelemetry.getDebugSnapshot(workspaceId, snapshotId)
        val params = telemetrySnapshot?.parameters ?: treeTelemetry.parameterSnapshot(treeProperties, featureFlags)
        val models = telemetrySnapshot?.models ?: mapOf(
            "embedding_provider" to embeddingProvider.providerId(),
            "embedding_model" to embeddingProvider.modelVersion(),
            "llm_provider" to llmTextGenerator.providerId(),
            "llm_model" to llmTextGenerator.modelVersion()
        )
        val decisions = telemetrySnapshot?.decisions ?: mapOf(
            "status" to snapshot.status,
            "moved_ratio" to snapshot.movedRatio,
            "churn_count" to snapshot.churnCount,
            "node_rename_count" to snapshot.nodeRenameCount,
            "unsorted_ratio" to unsortedRatio,
            "auto_ratio" to 0.0,
            "recommend_ratio" to 0.0
        )
        return mapOf(
            "snapshot_id" to snapshot.id,
            "status" to snapshot.status,
            "view_type" to TreeViewType.fromDb(snapshot.viewType).apiValue,
            "created_at" to snapshot.createdAt.toString(),
            "parameters" to params,
            "models" to models,
            "decision_summary" to decisions,
            "cluster_count" to clusterCount,
            "membership_count" to memberships.size,
            "unsorted_ratio" to unsortedRatio,
            "stage_logs" to telemetrySnapshot?.stageLogs.orEmpty().map { stage ->
                mapOf(
                    "stage" to stage.stage,
                    "duration_ms" to stage.durationMs,
                    "details" to stage.details
                )
            },
            "trace_id" to MDC.get("trace_id"),
            "request_id" to MDC.get("request_id")
        )
    }

    private fun buildSignals(
        wasLocked: Boolean,
        personalized: Boolean,
        ruled: Boolean,
        conceptPreassigned: Boolean,
        conceptSource: String? = null,
        optimizerAdjusted: Boolean,
        viewSignal: String? = null,
        quarantineReason: String? = null
    ): List<String> {
        val signals = mutableListOf<String>()
        if (wasLocked) signals += "LOCKED_NODE"
        if (personalized) signals += "PERSONALIZED_MOVE_SIGNAL"
        if (ruled) signals += "USER_RULE_MATCHED"
        if (conceptPreassigned) {
            signals += "CONCEPT_PREASSIGNED"
            conceptSource?.takeIf { it.isNotBlank() }?.let { source ->
                signals += "CONCEPT_SOURCE_${source.trim().uppercase()}"
            }
        }
        if (optimizerAdjusted) signals += "OBJECTIVE_OPTIMIZED"
        viewSignal?.takeIf { it.isNotBlank() }?.let { signals += it }
        quarantineReason?.takeIf { it.isNotBlank() }?.let { signals += it }
        if (signals.isEmpty()) {
            signals += "CLUSTER_DEFAULT"
        }
        return signals
    }

    private fun buildExplainEvidence(
        neighbors: List<NeighborLink>,
        documentsById: Map<String, DocumentRow>,
        limit: Int
    ): Map<String, Any?> {
        val trimmedNeighbors = neighbors
            .sortedByDescending { it.similarity }
            .take(limit.coerceIn(2, 3))
            .map { link ->
                mapOf(
                    "document_id" to link.documentId,
                    "title" to (documentsById[link.documentId]?.title ?: link.documentId),
                    "channel_scores" to mapOf(
                        "semantic" to link.semanticSimilarity,
                        "lexical" to link.lexicalSimilarity,
                        "final" to link.similarity
                    ),
                    "edge_decision" to mapOf(
                        "lexical_gate_passed" to link.lexicalGatePassed,
                        "reason_code" to link.reason,
                        "entity_overlap" to link.sharedEntityCount,
                        "title_overlap" to link.titleOverlap
                    )
                )
            }
        val reasonCodes = trimmedNeighbors
            .mapNotNull { neighbor ->
                val decision = neighbor["edge_decision"] as? Map<*, *> ?: return@mapNotNull null
                decision["reason_code"]?.toString()
            }
            .distinct()
        return mapOf(
            "neighbors" to trimmedNeighbors,
            "reason_codes" to reasonCodes
        )
    }

    private fun fallbackExplainSentence(
        keywords: List<String>,
        signals: List<String>,
        evidence: Map<String, Any?>
    ): String {
        val reasonCodes = (evidence["reason_codes"] as? List<*>).orEmpty().mapNotNull { it?.toString() }
        val reason = reasonCodes.firstOrNull()
            ?: signals.firstOrNull()
            ?: "CLUSTER_DEFAULT"
        val keyword = keywords.firstOrNull() ?: "문서 주제"
        val neighborCount = (evidence["neighbors"] as? List<*>)?.size ?: 0
        return "$keyword 관련 이웃 ${neighborCount}건과 신호($reason)를 근거로 자동 배치되었습니다."
    }

    private fun normalizeExplainRationale(raw: Map<String, Any?>): Map<String, Any?> {
        val keywords = raw["keywords"].safeStringList().take(5)
        val similarDocs = (raw["similar_docs"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val documentId = map["document_id"]?.toString() ?: return@mapNotNull null
            mapOf(
                "document_id" to documentId,
                "title" to (map["title"]?.toString() ?: documentId),
                "similarity" to (map["similarity"] as? Number)?.toDouble()
            )
        }.take(3)
        val signals = raw["signals"].safeStringList().take(5)
        val normalizedEvidence = normalizeExplainEvidence(raw["evidence"], similarDocs, signals)
        val llmSentence = raw["llm_sentence"]?.toString()?.takeIf { it.isNotBlank() }
            ?: fallbackExplainSentence(keywords, signals, normalizedEvidence)
        return mapOf(
            "keywords" to keywords,
            "similar_docs" to similarDocs,
            "signals" to signals,
            "evidence" to normalizedEvidence,
            "llm_sentence" to llmSentence
        )
    }

    private fun normalizeExplainEvidence(
        raw: Any?,
        similarDocs: List<Map<String, Any?>>,
        signals: List<String>
    ): Map<String, Any?> {
        val asMap = raw as? Map<*, *>
        val neighbors = (asMap?.get("neighbors") as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val documentId = map["document_id"]?.toString() ?: return@mapNotNull null
            val channelScores = map["channel_scores"] as? Map<*, *>
            val decision = map["edge_decision"] as? Map<*, *>
            mapOf(
                "document_id" to documentId,
                "title" to (map["title"]?.toString() ?: documentId),
                "channel_scores" to mapOf(
                    "semantic" to (channelScores?.get("semantic") as? Number)?.toDouble(),
                    "lexical" to (channelScores?.get("lexical") as? Number)?.toDouble(),
                    "final" to (channelScores?.get("final") as? Number)?.toDouble()
                ),
                "edge_decision" to mapOf(
                    "lexical_gate_passed" to ((decision?.get("lexical_gate_passed") as? Boolean) ?: false),
                    "reason_code" to (decision?.get("reason_code")?.toString() ?: "UNKNOWN"),
                    "entity_overlap" to (decision?.get("entity_overlap") as? Number)?.toInt(),
                    "title_overlap" to (decision?.get("title_overlap") as? Number)?.toInt()
                )
            )
        }.take(3)
        val fallbackNeighbors = similarDocs.take(2).map { similar ->
            mapOf(
                "document_id" to similar["document_id"],
                "title" to similar["title"],
                "channel_scores" to mapOf(
                    "semantic" to similar["similarity"],
                    "lexical" to null,
                    "final" to similar["similarity"]
                ),
                "edge_decision" to mapOf(
                    "lexical_gate_passed" to false,
                    "reason_code" to "SIMILAR_DOC_FALLBACK",
                    "entity_overlap" to null,
                    "title_overlap" to null
                )
            )
        }
        val finalNeighbors = if (neighbors.isNotEmpty()) neighbors else fallbackNeighbors
        val reasonCodes = ((asMap?.get("reason_codes") as? List<*>).orEmpty().mapNotNull { it?.toString() }
            .ifEmpty {
                finalNeighbors.mapNotNull { neighbor ->
                    val decision = neighbor["edge_decision"] as? Map<*, *> ?: return@mapNotNull null
                    decision["reason_code"]?.toString()
                }
            }
            .ifEmpty { signals })
            .distinct()
            .take(5)
        return mapOf(
            "neighbors" to finalNeighbors,
            "reason_codes" to reasonCodes
        )
    }

    private fun resolveQuarantineReason(rationale: Map<String, Any?>): String? {
        val ordered = linkedSetOf<String>()
        rationale["signals"]
            .safeStringList()
            .map { it.trim().uppercase() }
            .forEach { ordered += it }
        val reasonCodes = ((rationale["evidence"] as? Map<*, *>)?.get("reason_codes") as? List<*>).orEmpty()
            .mapNotNull { value -> value?.toString()?.trim()?.uppercase() }
        reasonCodes.forEach { ordered += it }
        return ordered.firstOrNull { it in unsortedReasonCodes }
    }

    private fun resolvePlacementConfidence(rationale: Map<String, Any?>): Double? {
        val neighbors = ((rationale["evidence"] as? Map<*, *>)?.get("neighbors") as? List<*>).orEmpty()
        val neighborScores = neighbors.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val channelScores = map["channel_scores"] as? Map<*, *> ?: return@mapNotNull null
            ((channelScores["final"] as? Number)?.toDouble()
                ?: (channelScores["semantic"] as? Number)?.toDouble()
                ?: (channelScores["lexical"] as? Number)?.toDouble())
                ?.coerceIn(0.0, 1.0)
        }.sortedDescending()

        if (neighborScores.isNotEmpty()) {
            val top1 = neighborScores[0]
            val top2 = neighborScores.getOrNull(1) ?: 0.0
            val margin = (top1 - top2).coerceIn(0.0, 1.0)
            return ((top1 * 0.7) + (margin * 0.3)).coerceIn(0.0, 1.0)
        }

        val similarScore = (rationale["similar_docs"] as? List<*>)
            .orEmpty()
            .firstNotNullOfOrNull { item ->
                val map = item as? Map<*, *> ?: return@firstNotNullOfOrNull null
                (map["similarity"] as? Number)?.toDouble()
            }
        return similarScore?.coerceIn(0.0, 1.0)
    }

    private fun buildPlacementCandidates(
        rationale: Map<String, Any?>,
        membershipByDocumentId: Map<String, TreeMembershipRow>,
        nodeById: Map<String, TreeNodeRow>,
        nodeDocumentCount: Map<String, Int>,
        currentNodeId: String
    ): List<Map<String, Any?>> {
        val scoreByNode = mutableMapOf<String, Double>()
        val labelByNode = mutableMapOf<String, String>()

        fun addCandidate(nodeId: String, label: String, score: Double, weight: Double) {
            if (nodeId == currentNodeId || !isPlacementCandidateLabel(label)) {
                return
            }
            val bounded = (score.coerceIn(0.0, 1.0) * weight).coerceIn(0.0, 1.0)
            if (bounded <= 0.0) {
                return
            }
            scoreByNode[nodeId] = (scoreByNode[nodeId] ?: 0.0) + bounded
            labelByNode.putIfAbsent(nodeId, label)
        }

        val evidenceNeighbors = ((rationale["evidence"] as? Map<*, *>)?.get("neighbors") as? List<*>).orEmpty()
        evidenceNeighbors.forEach { item ->
            val map = item as? Map<*, *> ?: return@forEach
            val neighborDocumentId = map["document_id"]?.toString() ?: return@forEach
            val neighborMembership = membershipByDocumentId[neighborDocumentId] ?: return@forEach
            val neighborNode = nodeById[neighborMembership.nodeId] ?: return@forEach
            val channelScores = map["channel_scores"] as? Map<*, *>
            val score = (channelScores?.get("final") as? Number)?.toDouble()
                ?: (channelScores?.get("semantic") as? Number)?.toDouble()
                ?: (channelScores?.get("lexical") as? Number)?.toDouble()
                ?: 0.0
            addCandidate(neighborNode.id, neighborNode.label, score, 1.0)
        }

        val similarDocs = (rationale["similar_docs"] as? List<*>).orEmpty()
        similarDocs.forEach { item ->
            val map = item as? Map<*, *> ?: return@forEach
            val neighborDocumentId = map["document_id"]?.toString() ?: return@forEach
            val neighborMembership = membershipByDocumentId[neighborDocumentId] ?: return@forEach
            val neighborNode = nodeById[neighborMembership.nodeId] ?: return@forEach
            val score = (map["similarity"] as? Number)?.toDouble() ?: 0.0
            addCandidate(neighborNode.id, neighborNode.label, score, 0.85)
        }

        if (scoreByNode.isEmpty()) {
            val fallbackNodes = nodeById.values
                .asSequence()
                .filter { node -> node.id != currentNodeId && node.depth >= 1 && isPlacementCandidateLabel(node.label) }
                .sortedWith(compareByDescending<TreeNodeRow> { nodeDocumentCount[it.id] ?: 0 }.thenBy { it.label })
                .take(3)
                .toList()
            val maxCount = fallbackNodes.maxOfOrNull { nodeDocumentCount[it.id] ?: 0 }?.coerceAtLeast(1) ?: 1
            fallbackNodes.forEach { node ->
                val base = ((nodeDocumentCount[node.id] ?: 0).toDouble() / maxCount.toDouble()).coerceIn(0.0, 1.0)
                val score = base.coerceIn(0.05, 0.60)
                scoreByNode[node.id] = score
                labelByNode[node.id] = node.label
            }
        }

        return scoreByNode.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { labelByNode[it.key] ?: it.key })
            .take(3)
            .map { (nodeId, score) ->
                mapOf(
                    "node_id" to nodeId,
                    "label" to (labelByNode[nodeId] ?: nodeId),
                    "score" to (kotlin.math.round(score.coerceIn(0.0, 1.0) * 1000.0) / 1000.0)
                )
            }
    }

    private fun applyViewProjection(
        viewType: TreeViewType,
        documents: List<DocumentRow>,
        assignment: Map<String, String>,
        frozenDocIds: Set<String>
    ): ViewProjectionOutcome {
        if (documents.isEmpty() || assignment.isEmpty() || viewType == TreeViewType.TOPIC) {
            return ViewProjectionOutcome(
                assignmentByDocument = assignment,
                metadataByDocument = emptyMap(),
                transformedDocCount = 0
            )
        }
        val documentById = documents.associateBy { document -> document.id }
        val projected = assignment.toMutableMap()
        val metadataByDocument = mutableMapOf<String, String>()
        var transformedDocCount = 0
        val versionFingerprintByDocument = if (viewType == TreeViewType.VERSION) {
            documents.associate { document ->
                document.id to versionFingerprint(document)
            }
        } else {
            emptyMap()
        }
        val versionChainSize = if (viewType == TreeViewType.VERSION) {
            versionFingerprintByDocument.values.groupingBy { it }.eachCount()
        } else {
            emptyMap()
        }

        assignment.forEach { (documentId, currentLabel) ->
            if (frozenDocIds.contains(documentId) || isUnsortedLabel(currentLabel)) {
                return@forEach
            }
            val document = documentById[documentId] ?: return@forEach
            val projectedLabel = when (viewType) {
                TreeViewType.TOPIC -> currentLabel
                TreeViewType.PROJECT -> {
                    metadataByDocument[documentId] = "VIEW_PROJECT"
                    projectLabelFor(document, currentLabel)
                }
                TreeViewType.TIMELINE -> {
                    metadataByDocument[documentId] = "VIEW_TIMELINE"
                    timelineLabelFor(document)
                }
                TreeViewType.VERSION -> {
                    val fingerprint = versionFingerprintByDocument[documentId] ?: return@forEach
                    if ((versionChainSize[fingerprint] ?: 0) >= 2) {
                        metadataByDocument[documentId] = "VIEW_VERSION_CHAIN"
                    } else {
                        metadataByDocument[documentId] = "VIEW_VERSION"
                    }
                    "version-${fingerprint.take(8)}"
                }
                TreeViewType.TEMPLATE -> {
                    metadataByDocument[documentId] = "VIEW_TEMPLATE"
                    templateLabelFor(document)
                }
            }
            if (projectedLabel != currentLabel) {
                projected[documentId] = projectedLabel
                transformedDocCount += 1
            }
        }
        return ViewProjectionOutcome(
            assignmentByDocument = projected,
            metadataByDocument = metadataByDocument,
            transformedDocCount = transformedDocCount
        )
    }

    private fun projectLabelFor(document: DocumentRow, fallbackLabel: String): String {
        val text = "${document.title} ${document.bodyText.orEmpty()}"
        val projectToken = Regex("(?:project|proj|프로젝트)\\s*[:#-]?\\s*([\\p{L}\\p{N}_-]{2,24})", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.lowercase()
        val base = projectToken
            ?.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            ?.takeIf { token -> token.isNotBlank() }
            ?: normalizeConceptKey(fallbackLabel)
        return "project-${base.take(24)}".take(40)
    }

    private fun timelineLabelFor(document: DocumentRow): String {
        val text = "${document.title} ${document.bodyText.orEmpty()}"
        val match = Regex("(20\\d{2})[./년\\-\\s]?([01]?\\d)").find(text)
        val year = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: document.updatedAt.year
        val month = match?.groupValues?.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 12) ?: document.updatedAt.monthValue
        return String.format("%04d-%02d", year, month)
    }

    private fun templateLabelFor(document: DocumentRow): String {
        val source = document.sourceType.trim().lowercase().ifBlank { "document" }
        return "template-${source.take(24)}".take(40)
    }

    private fun versionFingerprint(document: DocumentRow): String {
        val normalized = (document.title + " " + (document.bodyText ?: ""))
            .lowercase()
            .replace(Regex("\\b(v|ver|version|rev)[\\s._-]*\\d+\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(320)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun preassignByConcept(
        documents: List<DocumentRow>,
        embeddings: Map<String, EmbeddingRow>,
        activeConcepts: List<ConceptCandidate>
    ): ConceptPreassignOutcome {
        if (documents.isEmpty() || activeConcepts.isEmpty()) {
            return ConceptPreassignOutcome(
                assignmentByDocument = emptyMap(),
                confidenceByDocument = emptyMap(),
                sourceByDocument = emptyMap(),
                activeConceptCount = 0
            )
        }
        val eligibleConcepts = activeConcepts
            .filter { concept ->
                concept.docCount >= treeProperties.conceptMinDocs &&
                    isPlacementCandidateLabel(concept.label) &&
                    concept.vector.isNotEmpty()
            }
        if (eligibleConcepts.isEmpty()) {
            return ConceptPreassignOutcome(
                assignmentByDocument = emptyMap(),
                confidenceByDocument = emptyMap(),
                sourceByDocument = emptyMap(),
                activeConceptCount = 0
            )
        }
        val threshold = treeProperties.conceptAssignThreshold.coerceIn(0.0, 1.0)
        val assigned = mutableMapOf<String, String>()
        val confidenceByDocument = mutableMapOf<String, Double>()
        val sourceByDocument = mutableMapOf<String, String>()
        documents.forEach { document ->
            val vector = embeddings[document.id]?.let { parseVector(it.vectorJson) }.orEmpty()
            if (vector.isEmpty()) {
                return@forEach
            }
            val scored = eligibleConcepts
                .map { concept ->
                    val raw = cosine(vector, concept.vector).coerceIn(-1.0, 1.0)
                    val normalized = ((raw + 1.0) / 2.0).coerceIn(0.0, 1.0)
                    concept to normalized
                }
                .sortedByDescending { it.second }
            val best = scored.firstOrNull() ?: return@forEach
            val second = scored.getOrNull(1)?.second ?: 0.0
            val margin = (best.second - second).coerceIn(0.0, 1.0)
            val confidence = ((best.second * 0.75) + (margin * 0.25)).coerceIn(0.0, 1.0)
            if (best.second < threshold || confidence < threshold) {
                return@forEach
            }
            assigned[document.id] = best.first.label
            confidenceByDocument[document.id] = confidence
            sourceByDocument[document.id] = conceptSourceToken(best.first.label)
        }
        return ConceptPreassignOutcome(
            assignmentByDocument = assigned,
            confidenceByDocument = confidenceByDocument,
            sourceByDocument = sourceByDocument,
            activeConceptCount = eligibleConcepts.size
        )
    }

    private fun optimizeAssignment(
        documents: List<DocumentRow>,
        assignment: Map<String, String>,
        adjacency: Map<String, List<NeighborLink>>,
        previousDocToLabel: Map<String, String>,
        lockedLabelByDocument: Map<String, String>,
        frozenDocIds: Set<String>
    ): OptimizerOutcome {
        val baseline = assignment.toMutableMap()
        val before = buildObjectiveBreakdown(
            documents = documents,
            assignment = baseline,
            adjacency = adjacency,
            previousDocToLabel = previousDocToLabel,
            lockedLabelByDocument = lockedLabelByDocument
        )
        if (documents.isEmpty() || baseline.isEmpty()) {
            return OptimizerOutcome(
                assignmentByDocument = baseline,
                optimizedDocIds = emptySet(),
                iterations = 0,
                before = before,
                after = before
            )
        }
        val current = baseline.toMutableMap()
        val optimizedDocIds = mutableSetOf<String>()
        val maxIterations = treeProperties.optimizerMaxIterations.coerceAtLeast(1)
        val minImprovement = treeProperties.optimizerMinImprovement.coerceAtLeast(0.0)
        var iterations = 0

        for (iteration in 1..maxIterations) {
            var changed = false
            val labelCounts = current.values.groupingBy { it }.eachCount().toMutableMap()
            documents.forEach { document ->
                val documentId = document.id
                if (frozenDocIds.contains(documentId)) {
                    return@forEach
                }
                val currentLabel = current[documentId] ?: "general"
                val lockedLabel = lockedLabelByDocument[documentId]
                val candidateLabels = linkedSetOf<String>()
                candidateLabels += currentLabel
                previousDocToLabel[documentId]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { candidateLabels += it }
                adjacency[documentId]
                    .orEmpty()
                    .mapNotNull { neighbor -> current[neighbor.documentId] }
                    .filter { it.isNotBlank() }
                    .forEach { label -> candidateLabels += label }
                if (candidateLabels.size <= 1) {
                    return@forEach
                }
                var bestLabel = currentLabel
                var bestScore = localObjectiveScore(
                    documentId = documentId,
                    candidateLabel = currentLabel,
                    assignment = current,
                    labelCounts = labelCounts,
                    adjacency = adjacency,
                    previousDocToLabel = previousDocToLabel,
                    lockedLabelByDocument = lockedLabelByDocument
                )
                candidateLabels.forEach { candidateLabel ->
                    if (candidateLabel == currentLabel) {
                        return@forEach
                    }
                    if (lockedLabel != null && candidateLabel != lockedLabel) {
                        return@forEach
                    }
                    val candidateScore = localObjectiveScore(
                        documentId = documentId,
                        candidateLabel = candidateLabel,
                        assignment = current,
                        labelCounts = labelCounts,
                        adjacency = adjacency,
                        previousDocToLabel = previousDocToLabel,
                        lockedLabelByDocument = lockedLabelByDocument
                    )
                    if (candidateScore > bestScore + minImprovement) {
                        bestScore = candidateScore
                        bestLabel = candidateLabel
                    }
                }
                if (bestLabel == currentLabel) {
                    return@forEach
                }
                current[documentId] = bestLabel
                labelCounts[currentLabel] = (labelCounts[currentLabel] ?: 1) - 1
                labelCounts[bestLabel] = (labelCounts[bestLabel] ?: 0) + 1
                optimizedDocIds += documentId
                changed = true
            }
            if (!changed) {
                break
            }
            iterations = iteration
        }

        val after = buildObjectiveBreakdown(
            documents = documents,
            assignment = current,
            adjacency = adjacency,
            previousDocToLabel = previousDocToLabel,
            lockedLabelByDocument = lockedLabelByDocument
        )
        return OptimizerOutcome(
            assignmentByDocument = current,
            optimizedDocIds = optimizedDocIds,
            iterations = iterations,
            before = before,
            after = after
        )
    }

    private fun localObjectiveScore(
        documentId: String,
        candidateLabel: String,
        assignment: Map<String, String>,
        labelCounts: Map<String, Int>,
        adjacency: Map<String, List<NeighborLink>>,
        previousDocToLabel: Map<String, String>,
        lockedLabelByDocument: Map<String, String>
    ): Double {
        val currentLabel = assignment[documentId] ?: "general"
        val sameNeighbors = adjacency[documentId]
            .orEmpty()
            .filter { neighbor -> assignment[neighbor.documentId] == candidateLabel }
            .take(3)
        val fitScore = sameNeighbors
            .map { link -> link.similarity }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        val priorBonus = if (previousDocToLabel[documentId] == candidateLabel) 0.05 else 0.0
        val previousLabel = previousDocToLabel[documentId]
        val changePenalty = if (previousLabel != null && previousLabel != candidateLabel) 1.0 else 0.0
        val cannotPenalty = if (
            lockedLabelByDocument[documentId] != null &&
            lockedLabelByDocument[documentId] != candidateLabel
        ) {
            1.0
        } else {
            0.0
        }
        val currentCount = labelCounts[candidateLabel] ?: 0
        val predictedSize = currentCount + if (candidateLabel == currentLabel) 0 else 1
        val maxClusterSize = treeProperties.maxClusterSize.coerceAtLeast(1)
        val minClusterSize = treeProperties.minClusterSize.coerceAtLeast(1)
        val overPenalty = if (isUnsortedLabel(candidateLabel)) {
            0.0
        } else {
            ((predictedSize - maxClusterSize).coerceAtLeast(0)).toDouble() / maxClusterSize.toDouble()
        }
        val underPenalty = if (isUnsortedLabel(candidateLabel)) {
            0.0
        } else {
            ((minClusterSize - predictedSize).coerceAtLeast(0)).toDouble() / minClusterSize.toDouble()
        }
        val sizePenalty = overPenalty + (underPenalty * 0.35)
        return (fitScore + priorBonus) -
            (treeProperties.optimizerChangeCostLambda * changePenalty) -
            (treeProperties.optimizerCannotViolationMu * cannotPenalty) -
            (treeProperties.optimizerSizePenaltyNu * sizePenalty)
    }

    private fun buildObjectiveBreakdown(
        documents: List<DocumentRow>,
        assignment: Map<String, String>,
        adjacency: Map<String, List<NeighborLink>>,
        previousDocToLabel: Map<String, String>,
        lockedLabelByDocument: Map<String, String>
    ): ObjectiveBreakdown {
        if (documents.isEmpty() || assignment.isEmpty()) {
            return ObjectiveBreakdown(
                fitScore = 0.0,
                changeCost = 0.0,
                cannotViolations = 0,
                sizePenalty = 0.0,
                objectiveScore = 0.0
            )
        }
        val fitScore = documents
            .map { document ->
                val label = assignment[document.id] ?: "general"
                adjacency[document.id]
                    .orEmpty()
                    .filter { neighbor -> assignment[neighbor.documentId] == label }
                    .take(3)
                    .map { it.similarity }
                    .average()
                    .takeIf { !it.isNaN() } ?: 0.0
            }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        val changed = assignment.entries.count { (documentId, label) ->
            previousDocToLabel[documentId] != null && previousDocToLabel[documentId] != label
        }
        val changeCost = changed.toDouble() / assignment.size.toDouble().coerceAtLeast(1.0)
        val cannotViolations = assignment.entries.count { (documentId, label) ->
            val lockedLabel = lockedLabelByDocument[documentId] ?: return@count false
            lockedLabel != label
        }
        val cannotPenalty = cannotViolations.toDouble() / assignment.size.toDouble().coerceAtLeast(1.0)
        val maxClusterSize = treeProperties.maxClusterSize.coerceAtLeast(1)
        val minClusterSize = treeProperties.minClusterSize.coerceAtLeast(1)
        val clusterPenalty = assignment.values
            .groupingBy { it }
            .eachCount()
            .entries
            .filter { (label, _) -> !isUnsortedLabel(label) }
            .map { (_, size) ->
                val overPenalty = ((size - maxClusterSize).coerceAtLeast(0)).toDouble() / maxClusterSize.toDouble()
                val underPenalty = ((minClusterSize - size).coerceAtLeast(0)).toDouble() / minClusterSize.toDouble()
                overPenalty + (underPenalty * 0.35)
            }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        val objectiveScore = fitScore -
            (treeProperties.optimizerChangeCostLambda * changeCost) -
            (treeProperties.optimizerCannotViolationMu * cannotPenalty) -
            (treeProperties.optimizerSizePenaltyNu * clusterPenalty)
        return ObjectiveBreakdown(
            fitScore = fitScore,
            changeCost = changeCost,
            cannotViolations = cannotViolations,
            sizePenalty = clusterPenalty,
            objectiveScore = objectiveScore
        )
    }

    private fun buildSnapshotConceptRows(
        workspaceId: String,
        snapshotId: String,
        assignment: Map<String, String>,
        embeddings: Map<String, EmbeddingRow>,
        previousConcepts: List<ConceptCandidate>
    ): List<ConceptPrototypeRow> {
        if (assignment.isEmpty()) {
            return emptyList()
        }
        val vectorsByDocument = embeddings
            .mapValues { (_, embedding) -> parseVector(embedding.vectorJson) }
            .filterValues { vector -> vector.isNotEmpty() }
        if (vectorsByDocument.isEmpty()) {
            return emptyList()
        }
        val previousByLabel = previousConcepts.associateBy { concept -> normalizeConceptKey(concept.label) }
        val now = LocalDateTime.now()
        return assignment.entries
            .groupBy(
                keySelector = { entry -> entry.value },
                valueTransform = { entry -> entry.key }
            )
            .asSequence()
            .filter { (label, docIds) ->
                isPlacementCandidateLabel(label) && docIds.size >= treeProperties.conceptMinDocs
            }
            .mapNotNull { (label, docIds) ->
                val vectors = docIds.mapNotNull { documentId -> vectorsByDocument[documentId] }
                if (vectors.size < treeProperties.conceptMinDocs) {
                    return@mapNotNull null
                }
                val centroid = averageVector(vectors)
                if (centroid.isEmpty()) {
                    return@mapNotNull null
                }
                val previousVector = previousByLabel[normalizeConceptKey(label)]?.vector
                val prototypeVector = if (previousVector.isNullOrEmpty()) {
                    centroid
                } else {
                    blendVector(
                        previous = previousVector,
                        current = centroid,
                        alpha = treeProperties.conceptUpdateAlpha
                    )
                }
                val exemplarDocIds = docIds
                    .mapNotNull { documentId ->
                        val vector = vectorsByDocument[documentId] ?: return@mapNotNull null
                        documentId to cosine(prototypeVector, vector)
                    }
                    .sortedByDescending { it.second }
                    .take(3)
                    .map { it.first }
                val driftScore = if (previousVector.isNullOrEmpty()) {
                    0.0
                } else {
                    val similarity = cosine(previousVector, prototypeVector).coerceIn(-1.0, 1.0)
                    (1.0 - ((similarity + 1.0) / 2.0)).coerceIn(0.0, 1.0)
                }
                ConceptPrototypeRow(
                    id = UUID.randomUUID().toString(),
                    workspaceId = workspaceId,
                    snapshotId = snapshotId,
                    conceptKey = normalizeConceptKey(label),
                    label = label.take(255),
                    prototypeVectorJson = objectMapper.writeValueAsString(prototypeVector),
                    exemplarDocIdsJson = objectMapper.writeValueAsString(exemplarDocIds),
                    docCount = docIds.size,
                    driftScore = driftScore,
                    createdAt = now,
                    updatedAt = now
                )
            }
            .sortedWith(compareByDescending<ConceptPrototypeRow> { it.docCount }.thenBy { it.label })
            .toList()
    }

    private fun averageVector(vectors: List<List<Double>>): List<Double> {
        if (vectors.isEmpty()) {
            return emptyList()
        }
        val size = vectors.minOfOrNull { vector -> vector.size } ?: 0
        if (size == 0) {
            return emptyList()
        }
        val aggregate = DoubleArray(size)
        vectors.forEach { vector ->
            for (index in 0 until size) {
                aggregate[index] += vector[index]
            }
        }
        return aggregate.map { value -> value / vectors.size.toDouble() }
    }

    private fun blendVector(previous: List<Double>, current: List<Double>, alpha: Double): List<Double> {
        val size = minOf(previous.size, current.size)
        if (size == 0) {
            return current
        }
        val boundedAlpha = alpha.coerceIn(0.0, 1.0)
        val previousWeight = 1.0 - boundedAlpha
        return (0 until size).map { index ->
            (previous[index] * previousWeight) + (current[index] * boundedAlpha)
        }
    }

    private fun normalizeConceptKey(label: String): String {
        val normalized = label.trim()
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
            .trim('_')
        return if (normalized.isBlank()) "concept" else normalized.take(120)
    }

    private fun conceptSourceToken(label: String): String {
        val token = normalizeConceptKey(label)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .uppercase()
        return if (token.isBlank()) "UNKNOWN" else token.take(40)
    }

    private fun parseVector(vectorJson: String): List<Double> {
        return runCatching {
            objectMapper.readValue(vectorJson, List::class.java).mapNotNull { value ->
                (value as? Number)?.toDouble()
            }
        }.getOrElse { emptyList() }
    }

    private fun parseRationale(json: String): Map<String, Any?> {
        return runCatching {
            val raw = objectMapper.readValue(json, Map::class.java) as Map<*, *>
            raw.entries.associate { (key, value) -> key.toString() to value }
        }.getOrElse {
            mapOf(
                "keywords" to emptyList<String>(),
                "similar_docs" to emptyList<Map<String, Any?>>(),
                "signals" to emptyList<String>(),
                "evidence" to mapOf(
                    "neighbors" to emptyList<Map<String, Any?>>(),
                    "reason_codes" to emptyList<String>()
                ),
                "llm_sentence" to null
            )
        }
    }

    private fun importWorkerClusters(
        workspaceId: String,
        documents: List<DocumentRow>,
        graph: NeighborGraph
    ): List<TreeCluster> {
        val expectedDocIds = documents.map { it.id }.toSet()
        if (expectedDocIds.isEmpty()) {
            return emptyList()
        }
        val imported = structureWorkerClient.inferClusters(workspaceId, documents, graph)
        if (imported.isEmpty()) {
            return emptyList()
        }
        val assigned = mutableSetOf<String>()
        val validated = imported.mapNotNull { cluster ->
            val members = cluster.documentIds
                .filter { documentId -> expectedDocIds.contains(documentId) && assigned.add(documentId) }
                .distinct()
            if (members.isEmpty()) {
                return@mapNotNull null
            }
            TreeCluster(
                id = cluster.id,
                documentIds = members,
                qualityScore = cluster.qualityScore
            )
        }.toMutableList()
        if (validated.isEmpty()) {
            return emptyList()
        }
        val missingDocIds = expectedDocIds - assigned
        var singletonIndex = 0
        missingDocIds.sorted().forEach { documentId ->
            singletonIndex += 1
            validated += TreeCluster(
                id = "worker-singleton-$singletonIndex",
                documentIds = listOf(documentId),
                qualityScore = 0.0
            )
        }
        return validated
    }

    private fun buildRerankerTextByDocument(
        workspaceId: String,
        documents: List<DocumentRow>
    ): Map<String, String> {
        if (documents.isEmpty()) {
            return emptyMap()
        }
        return documents.associate { document ->
            val bodySummary = compactWhitespace(document.bodyText.orEmpty()).take(240)
            val topSections = documentSectionRepository
                .listByWorkspaceAndDocument(workspaceId, document.id)
                .take(3)
                .joinToString(" ") { section ->
                    val heading = compactWhitespace(section.heading.orEmpty()).take(48)
                    val chunk = compactWhitespace(section.chunkText.orEmpty()).take(120)
                    listOf(heading, chunk)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                }
            val rerankerInput = listOf(
                compactWhitespace(document.title).take(120),
                bodySummary,
                compactWhitespace(topSections).take(420)
            )
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .take(900)
            document.id to rerankerInput
        }
    }

    private fun compactWhitespace(value: String): String {
        return value.replace(Regex("\\s+"), " ").trim()
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
                val normalizedType = userRuleMatcher.normalizeRuleType(row.ruleType)
                if (normalizedType !in UserRuleMatcher.SUPPORTED_RULE_TYPES) {
                    return@mapNotNull null
                }
                ResolvedUserRule(
                    id = row.id,
                    ruleType = normalizedType,
                    ruleValue = value,
                    targetLabel = label,
                    ruleEffect = userRuleMatcher.normalizeRuleEffect(row.ruleEffect)
                )
            }
            .distinctBy { "${it.ruleType}::${it.ruleValue}::${it.ruleEffect}::${it.targetLabel}" }
    }

    private fun buildRuleContextByDocument(
        workspaceId: String,
        documents: List<DocumentRow>
    ): Map<String, UserRuleMatchContext> {
        if (documents.isEmpty()) {
            return emptyMap()
        }
        val attachmentsByDocument = attachmentRepository.listByWorkspace(workspaceId).groupBy { it.documentId }
        return documents.associate { document ->
            val filenameExtensions = attachmentsByDocument[document.id].orEmpty()
                .mapNotNull { attachment -> extractFilenameExtension(attachment.filename) }
                .toSet()
            document.id to UserRuleMatchContext(
                filenameExtensions = filenameExtensions,
                tags = userRuleMatcher.extractTags(document)
            )
        }
    }

    private fun extractFilenameExtension(filename: String): String? {
        val normalized = filename.trim()
        if (normalized.isBlank()) {
            return null
        }
        val leaf = normalized.substringAfterLast('/').substringAfterLast('\\')
        val dotIndex = leaf.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == leaf.length - 1) {
            return null
        }
        return leaf.substring(dotIndex + 1).trim().lowercase().takeIf { it.isNotBlank() }
    }

    private fun applySoftRules(
        documents: List<DocumentRow>,
        adjacency: Map<String, List<NeighborLink>>,
        assignment: MutableMap<String, String>,
        preferredLabels: Map<String, String>
    ): Set<String> {
        if (documents.isEmpty() || preferredLabels.isEmpty()) {
            return emptySet()
        }
        val applied = mutableSetOf<String>()
        documents.forEach { doc ->
            val preferredLabel = preferredLabels[doc.id] ?: return@forEach
            val currentLabel = assignment[doc.id] ?: return@forEach
            if (currentLabel.equals(preferredLabel, ignoreCase = true)) {
                return@forEach
            }
            val confidence = estimateAssignmentConfidence(adjacency[doc.id].orEmpty())
            if (confidence < 0.72) {
                assignment[doc.id] = preferredLabel
                applied += doc.id
            }
        }
        return applied
    }

    private fun loadTreeEmbeddings(workspaceId: String, documents: List<DocumentRow>): Map<String, EmbeddingRow> {
        if (documents.isEmpty()) {
            return emptyMap()
        }
        val modelVersion = embeddingProvider.modelVersion()
        val rows = embeddingRepository.listByWorkspaceAndModel(workspaceId, modelVersion)
        if (rows.isEmpty()) {
            return emptyMap()
        }
        val qualityByDocument = documents.associate { document ->
            val sections = documentSectionRepository.listByWorkspaceAndDocument(workspaceId, document.id)
            val quality = embeddingQualityScorer.score(
                bodyText = document.bodyText ?: document.bodyMarkdown ?: "",
                sections = sections
            )
            document.id to EmbeddingQualityWeights(
                body = quality.bodyWeight(),
                section = quality.sectionWeight()
            )
        }
        return embeddingAggregationService.aggregateForTree(
            embeddings = rows,
            treeProperties = treeProperties,
            qualityByDocument = qualityByDocument
        )
    }

    private fun applyQuarantinePolicies(
        workspaceId: String,
        documents: List<DocumentRow>,
        adjacency: Map<String, List<NeighborLink>>,
        assignment: MutableMap<String, String>
    ): Map<String, String> {
        if (documents.isEmpty()) {
            return emptyMap()
        }
        val reasons = mutableMapOf<String, String>()
        val degreeByDoc = documents.associate { doc ->
            doc.id to adjacency[doc.id].orEmpty().size
        }
        val avgDegree = degreeByDoc.values.average().takeIf { !it.isNaN() } ?: 0.0
        val hubThreshold = maxOf(3.0, avgDegree * 1.8, treeProperties.neighborEdgeBudget.toDouble())

        documents.forEach { doc ->
            val links = adjacency[doc.id].orEmpty()
            if (links.isEmpty()) {
                return@forEach
            }
            val top1 = links.getOrNull(0)?.similarity ?: 0.0
            val top2 = links.getOrNull(1)?.similarity ?: 0.0
            val confidenceMargin = (top1 - top2).coerceIn(0.0, 1.0)
            val neighborLabels = links
                .mapNotNull { neighbor -> assignment[neighbor.documentId] }
                .toSet()
            val diversity = if (links.isEmpty()) 0.0 else neighborLabels.size.toDouble() / links.size.toDouble()

            val sections = documentSectionRepository.listByWorkspaceAndDocument(workspaceId, doc.id)
            val noisySections = sections.count { section ->
                section.qualityFlags
                    ?.split(',')
                    ?.map { it.trim().uppercase() }
                    ?.any { flag -> flag in setOf("GIBBERISH", "TOO_SHORT", "ZERO_LENGTH") } == true
            }
            val templateLikely = sections.isNotEmpty() && noisySections.toDouble() / sections.size.toDouble() >= 0.6
            val degree = degreeByDoc[doc.id] ?: 0

            val reason = when {
                templateLikely -> "TEMPLATE"
                degree.toDouble() >= hubThreshold -> "HUB"
                confidenceMargin < 0.06 -> "LOW_CONFIDENCE"
                diversity >= 0.45 && confidenceMargin < 0.14 -> "CONFLICT"
                else -> null
            } ?: return@forEach

            assignment[doc.id] = "general"
            reasons[doc.id] = reason
            when (reason) {
                "HUB" -> {
                    hubDocCounter.increment()
                    unsortedHubCounter.increment()
                }
                "LOW_CONFIDENCE" -> unsortedLowConfidenceCounter.increment()
                "CONFLICT" -> unsortedConflictCounter.increment()
                "TEMPLATE" -> unsortedTemplateCounter.increment()
            }
        }
        return reasons
    }

    private fun resolveAssignmentPolicy(workspaceId: String): AssignmentPolicy {
        val policyOverride = workspaceTreePolicyRepository.findByWorkspace(workspaceId)
        val source = if (policyOverride == null) "DEFAULT" else "OVERRIDE"
        val auto = (policyOverride?.autoThreshold ?: treeProperties.assignAutoThreshold).coerceIn(0.0, 1.0)
        val recommend = (policyOverride?.recommendThreshold ?: treeProperties.assignRecommendThreshold).coerceIn(0.0, 1.0)
        val normalizedRecommend = minOf(auto, recommend)
        return AssignmentPolicy(
            autoThreshold = auto,
            recommendThreshold = normalizedRecommend,
            quarantineEnabled = policyOverride?.quarantineEnabled ?: treeProperties.assignQuarantineEnabled,
            rerankerEnabled = policyOverride?.rerankerEnabled ?: treeProperties.assignRerankerEnabled,
            structureWorkerEnabled = treeProperties.structureWorkerEnabled,
            source = source
        )
    }

    private fun applyAssignmentPolicy(
        workspaceId: String,
        documents: List<DocumentRow>,
        adjacency: Map<String, List<NeighborLink>>,
        assignment: MutableMap<String, String>,
        existingReasons: Map<String, String>,
        feedbackEvents: List<FeedbackEventRow>,
        policy: AssignmentPolicy
    ): AssignmentPolicyOutcome {
        if (documents.isEmpty()) {
            return AssignmentPolicyOutcome(
                confidenceByDocument = emptyMap(),
                decisionByDocument = emptyMap(),
                reasonByDocument = emptyMap()
            )
        }

        val confidenceByDocument = mutableMapOf<String, Double>()
        val decisionByDocument = mutableMapOf<String, String>()
        val reasonByDocument = mutableMapOf<String, String>()
        val correctionEvents = feedbackEvents.count { event ->
            event.eventType.equals("MOVE", ignoreCase = true) ||
                event.eventType.equals("RENAME", ignoreCase = true)
        }
        val correctionRate = if (feedbackEvents.isEmpty()) {
            0.0
        } else {
            correctionEvents.toDouble() / feedbackEvents.size.toDouble()
        }

        logger.info(
            "assignment_policy_applied workspace_id={} source={} auto_threshold={} recommend_threshold={} quarantine_enabled={} structure_worker_enabled={} correction_rate={}",
            workspaceId,
            policy.source,
            String.format("%.3f", policy.autoThreshold),
            String.format("%.3f", policy.recommendThreshold),
            policy.quarantineEnabled,
            policy.structureWorkerEnabled,
            String.format("%.3f", correctionRate)
        )

        documents.forEach { doc ->
            val calibrated = calibrateConfidence(
                raw = estimateAssignmentConfidence(adjacency[doc.id].orEmpty()),
                correctionRate = correctionRate
            )
            confidenceByDocument[doc.id] = calibrated

            if (existingReasons.containsKey(doc.id)) {
                decisionByDocument[doc.id] = "UNSORTED"
                policyDecisionUnsortedCounter.increment()
                return@forEach
            }
            if (!policy.quarantineEnabled) {
                decisionByDocument[doc.id] = "AUTO"
                policyDecisionAutoCounter.increment()
                return@forEach
            }

            val decision = when {
                calibrated >= policy.autoThreshold -> "AUTO"
                calibrated >= policy.recommendThreshold -> "RECOMMEND"
                else -> "UNSORTED"
            }
            decisionByDocument[doc.id] = decision
            when (decision) {
                "AUTO" -> policyDecisionAutoCounter.increment()
                "RECOMMEND" -> {
                    assignment[doc.id] = "general"
                    reasonByDocument[doc.id] = "RECOMMEND"
                    policyDecisionRecommendCounter.increment()
                }
                else -> {
                    assignment[doc.id] = "general"
                    reasonByDocument[doc.id] = "LOW_CONFIDENCE"
                    policyDecisionUnsortedCounter.increment()
                }
            }
        }

        return AssignmentPolicyOutcome(
            confidenceByDocument = confidenceByDocument,
            decisionByDocument = decisionByDocument,
            reasonByDocument = reasonByDocument
        )
    }

    private fun estimateAssignmentConfidence(links: List<NeighborLink>): Double {
        if (links.isEmpty()) {
            return 0.0
        }
        val top1 = links.getOrNull(0)?.similarity ?: 0.0
        val top2 = links.getOrNull(1)?.similarity ?: 0.0
        val margin = (top1 - top2).coerceIn(0.0, 1.0)
        return ((top1 * 0.7) + (margin * 0.3)).coerceIn(0.0, 1.0)
    }

    private fun calibrateConfidence(raw: Double, correctionRate: Double): Double {
        val boundedRaw = raw.coerceIn(0.0, 1.0)
        val temperature = 1.0 + (correctionRate * 2.0)
        val scaled = ((boundedRaw - 0.5) * 4.0) / temperature
        return (1.0 / (1.0 + kotlin.math.exp(-scaled))).coerceIn(0.0, 1.0)
    }

    private fun parseLabelCache(labelCacheJson: String?): Map<String, String> {
        if (labelCacheJson.isNullOrBlank()) {
            return emptyMap()
        }
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(labelCacheJson, Map::class.java) as Map<String, String>
        }.getOrElse { emptyMap() }
    }

    private fun maskedText(value: String): Map<String, Any?> {
        val normalized = value.trim()
        val hashBytes = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        val hash = hashBytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        return mapOf(
            "hash" to "sha256:$hash",
            "length" to normalized.length
        )
    }

    private fun resolveViewType(requested: TreeViewType): TreeViewType {
        if (requested != TreeViewType.TOPIC && !treeProperties.multiviewEnabled) {
            return TreeViewType.TOPIC
        }
        return requested
    }

    private fun isUnsortedLabel(label: String): Boolean {
        return label.equals("general", ignoreCase = true) || label.equals("unsorted", ignoreCase = true)
    }

    private fun isPlacementCandidateLabel(label: String): Boolean {
        val normalized = label.trim().lowercase()
        if (normalized.isBlank()) {
            return false
        }
        return normalized !in setOf("autodoc", "general", "unsorted")
    }

    private data class TraceContextState(
        val generatedTraceId: Boolean,
        val generatedRequestId: Boolean
    )

    private fun ensureTraceContext(): TraceContextState {
        var generatedTraceId = false
        var generatedRequestId = false
        if (MDC.get("trace_id").isNullOrBlank()) {
            MDC.put("trace_id", UUID.randomUUID().toString())
            generatedTraceId = true
        }
        if (MDC.get("request_id").isNullOrBlank()) {
            MDC.put("request_id", "rebuild-${UUID.randomUUID()}")
            generatedRequestId = true
        }
        return TraceContextState(
            generatedTraceId = generatedTraceId,
            generatedRequestId = generatedRequestId
        )
    }

    private fun clearTraceContext(state: TraceContextState) {
        if (state.generatedTraceId) {
            MDC.remove("trace_id")
        }
        if (state.generatedRequestId) {
            MDC.remove("request_id")
        }
    }

    private fun Any?.safeStringList(): List<String> {
        val raw = this as? List<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            item?.toString()
        }
    }

}

@Service
class FeedbackService(
    private val documentRepository: DocumentRepository,
    private val treeRepository: TreeRepository,
    private val feedbackRepository: FeedbackRepository,
    private val outboxService: OutboxService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {
    private val moveCounter = meterRegistry.counter("feedback_move_total")

    private val moveSourceAllowList = setOf("DRAG", "MANUAL", "QUICK_CONFIRM", "QUESTION", "UNKNOWN")

    @Transactional
    fun move(context: WorkspaceContext, documentId: String, fromNodeId: String?, toNodeId: String, source: String?) {
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
        val sourceLabel = source
            ?.trim()
            ?.uppercase()
            ?.takeIf { it in moveSourceAllowList }
            ?: "UNKNOWN"

        val payload = mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "document_id" to documentId,
            "from_node_id" to fromNodeId,
            "to_node_id" to toNode.id,
            "source" to sourceLabel
        )
        val payloadJson = objectMapper.writeValueAsString(payload)
        feedbackRepository.insert(context.workspaceId, context.userId, "MOVE", payloadJson)
        moveCounter.increment()
        meterRegistry.counter("feedback_move_source_total", "source", sourceLabel).increment()
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
    private val meterRegistry: MeterRegistry
) {
    private val debugNeighborsCalledCounter = meterRegistry.counter("debug_neighbors_called_total")
    private val debugDocumentCalledCounter = meterRegistry.counter("debug_document_called_total")
    private val debugClusterCalledCounter = meterRegistry.counter("debug_cluster_called_total")
    private val debugRebuildCalledCounter = meterRegistry.counter("debug_rebuild_called_total")

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
