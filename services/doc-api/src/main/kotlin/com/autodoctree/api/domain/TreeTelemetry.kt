package com.autodoctree.api.domain

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.config.TreeProperties
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class TreeStageLog(
    val stage: String,
    val durationMs: Double,
    val details: Map<String, Any?> = emptyMap()
)

data class TreeRebuildDebugSnapshot(
    val workspaceId: String,
    val snapshotId: String,
    val createdAt: LocalDateTime,
    val stageLogs: List<TreeStageLog>,
    val parameters: Map<String, Any?>,
    val models: Map<String, Any?>,
    val decisions: Map<String, Any?>
)

data class TreeRebuildTrace(
    val workspaceId: String,
    val startedAtNanos: Long = System.nanoTime(),
    val stageLogs: MutableList<TreeStageLog> = mutableListOf()
)

@Service
class TreeTelemetry(
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val rebuildDurationSummary = meterRegistry.summary("tree.rebuild.duration.ms")
    private val stageDurationSummary = meterRegistry.summary("tree.rebuild.stage.duration.ms")
    private val similarityDistribution = meterRegistry.summary("tree.rebuild.similarity.distribution")
    private val degreeDistribution = meterRegistry.summary("tree.rebuild.degree.distribution")
    private val clusterSizeDistribution = meterRegistry.summary("tree.rebuild.cluster_size.distribution")
    private val unsortedRatioSummary = meterRegistry.summary("tree.rebuild.unsorted_ratio")

    private val stageLogBySnapshot = ConcurrentHashMap<String, TreeRebuildDebugSnapshot>()
    private val snapshotOrder = ConcurrentLinkedQueue<String>()
    private val snapshotRetentionLimit = 256

    fun begin(workspaceId: String): TreeRebuildTrace {
        return TreeRebuildTrace(workspaceId = workspaceId)
    }

    fun complete(trace: TreeRebuildTrace): Double {
        val durationMs = nanosToMs(System.nanoTime() - trace.startedAtNanos)
        rebuildDurationSummary.record(durationMs)
        return durationMs
    }

    fun recordStage(
        trace: TreeRebuildTrace,
        stage: String,
        startedAtNanos: Long,
        details: Map<String, Any?> = emptyMap()
    ): TreeStageLog {
        val log = TreeStageLog(
            stage = stage,
            durationMs = nanosToMs(System.nanoTime() - startedAtNanos),
            details = details
        )
        trace.stageLogs += log
        stageDurationSummary.record(log.durationMs)
        logger.info("tree_rebuild_stage {}", buildStageLogPayload(trace.workspaceId, log))
        return log
    }

    fun recordGraphDistribution(graph: NeighborGraph) {
        graph.adjacency.values.forEach { neighbors ->
            degreeDistribution.record(neighbors.size.toDouble())
            neighbors.forEach { link ->
                similarityDistribution.record(link.similarity)
            }
        }
    }

    fun recordClusterDistribution(clusters: List<TreeCluster>) {
        clusters.forEach { cluster ->
            clusterSizeDistribution.record(cluster.documentIds.size.toDouble())
        }
    }

    fun recordUnsortedRatio(ratio: Double) {
        unsortedRatioSummary.record(ratio.coerceIn(0.0, 1.0))
    }

    fun buildStageLogPayload(workspaceId: String, stageLog: TreeStageLog): Map<String, Any?> {
        return mapOf(
            "event" to "tree_rebuild_stage",
            "workspace_id" to workspaceId,
            "stage" to stageLog.stage,
            "duration_ms" to format3(stageLog.durationMs),
            "trace_id" to MDC.get("trace_id"),
            "request_id" to MDC.get("request_id"),
            "details" to stageLog.details
        )
    }

    fun buildSummaryPayload(
        workspaceId: String,
        snapshotId: String?,
        documentCount: Int,
        status: String,
        movedRatio: Double,
        churnRatio: Double,
        unsortedRatio: Double,
        graphStats: NeighborBuildStats,
        stageLogs: List<TreeStageLog>
    ): Map<String, Any?> {
        return mapOf(
            "event" to "tree_rebuild_summary",
            "workspace_id" to workspaceId,
            "snapshot_id" to snapshotId,
            "status" to status,
            "document_count" to documentCount,
            "edge_count" to graphStats.edgeCount,
            "filtered_edge_count" to graphStats.filteredEdgeCount,
            "avg_similarity" to format3(graphStats.averageSimilarity),
            "moved_ratio" to format3(movedRatio),
            "churn_ratio" to format3(churnRatio),
            "unsorted_ratio" to format3(unsortedRatio),
            "stage_count" to stageLogs.size,
            "stage_durations_ms" to stageLogs.associate { it.stage to format3(it.durationMs) },
            "trace_id" to MDC.get("trace_id"),
            "request_id" to MDC.get("request_id")
        )
    }

    fun logSummary(payload: Map<String, Any?>) {
        logger.info("tree_rebuild_summary {}", payload)
    }

    fun storeDebugSnapshot(snapshot: TreeRebuildDebugSnapshot) {
        val key = key(snapshot.workspaceId, snapshot.snapshotId)
        stageLogBySnapshot[key] = snapshot
        snapshotOrder += key
        trimRetention()
    }

    fun getDebugSnapshot(workspaceId: String, snapshotId: String): TreeRebuildDebugSnapshot? {
        return stageLogBySnapshot[key(workspaceId, snapshotId)]
    }

    fun parameterSnapshot(tree: TreeProperties, flags: FeatureFlags): Map<String, Any?> {
        return mapOf(
            "neighbor_top_k" to tree.neighborTopK,
            "neighbor_min_similarity" to tree.neighborMinSimilarity,
            "neighbor_min_similarity_auto" to tree.neighborMinSimilarityAuto,
            "neighbor_min_similarity_auto_margin" to tree.neighborMinSimilarityAutoMargin,
            "neighbor_normalize" to tree.neighborNormalize,
            "neighbor_mutual_knn" to tree.neighborMutualKnn,
            "neighbor_shared_neighbor_jaccard_min" to tree.neighborSharedNeighborJaccardMin,
            "neighbor_edge_budget" to tree.neighborEdgeBudget,
            "neighbor_degree_cap" to tree.neighborDegreeCap,
            "neighbor_bridge_prune_policy" to tree.neighborBridgePrunePolicy,
            "assign_auto_threshold" to tree.assignAutoThreshold,
            "assign_recommend_threshold" to tree.assignRecommendThreshold,
            "assign_quarantine_enabled" to tree.assignQuarantineEnabled,
            "assign_reranker_enabled" to tree.assignRerankerEnabled,
            "structure_worker_enabled" to tree.structureWorkerEnabled,
            "multiview_enabled" to tree.multiviewEnabled,
            "concept_enabled" to tree.conceptEnabled,
            "concept_assign_threshold" to tree.conceptAssignThreshold,
            "concept_min_docs" to tree.conceptMinDocs,
            "concept_update_alpha" to tree.conceptUpdateAlpha,
            "optimizer_enabled" to tree.optimizerEnabled,
            "optimizer_max_iterations" to tree.optimizerMaxIterations,
            "optimizer_change_cost_lambda" to tree.optimizerChangeCostLambda,
            "optimizer_cannot_violation_mu" to tree.optimizerCannotViolationMu,
            "optimizer_size_penalty_nu" to tree.optimizerSizePenaltyNu,
            "optimizer_min_improvement" to tree.optimizerMinImprovement,
            "consensus_enabled" to tree.consensusEnabled,
            "consensus_threshold" to tree.consensusThreshold,
            "max_cluster_size" to tree.maxClusterSize,
            "min_cluster_size" to tree.minClusterSize,
            "cluster_merge_min_affinity" to tree.clusterMergeMinAffinity,
            "cluster_split_retry_with_higher_resolution" to tree.clusterSplitRetryWithHigherResolution,
            "cluster_split_retry_resolution_multiplier" to tree.clusterSplitRetryResolutionMultiplier,
            "community_resolution" to tree.communityResolution,
            "fusion_semantic_weight" to tree.fusionSemanticWeight,
            "fusion_lexical_weight" to tree.fusionLexicalWeight,
            "fusion_lexical_gate" to tree.fusionLexicalGate,
            "reranker_per_doc_budget" to tree.rerankerPerDocBudget,
            "reranker_pass_threshold" to tree.rerankerPassThreshold,
            "feature_flags" to mapOf(
                "auto_tree" to flags.autoTree,
                "community_clustering" to flags.communityClustering,
                "label_quality_filter" to flags.labelQualityFilter,
                "nori_tokenizer" to flags.noriTokenizer,
                "feedback_routing_v2" to flags.feedbackRoutingV2,
                "user_rules_v1" to flags.userRulesV1,
                "admin_tree_debug" to flags.adminTreeDebug
            )
        )
    }

    private fun trimRetention() {
        while (snapshotOrder.size > snapshotRetentionLimit) {
            val oldest = snapshotOrder.poll() ?: break
            stageLogBySnapshot.remove(oldest)
        }
    }

    private fun key(workspaceId: String, snapshotId: String): String = "$workspaceId::$snapshotId"

    private fun nanosToMs(nanos: Long): Double = nanos.toDouble() / 1_000_000.0

    private fun format3(value: Double): String = String.format("%.3f", value)
}
