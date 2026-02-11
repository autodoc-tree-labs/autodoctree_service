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
            "neighbor_normalize" to tree.neighborNormalize,
            "neighbor_mutual_knn_required" to tree.neighborMutualKnnRequired,
            "neighbor_snn_threshold" to tree.neighborSnnThreshold,
            "neighbor_edge_budget" to tree.neighborEdgeBudget,
            "max_cluster_size" to tree.maxClusterSize,
            "min_cluster_size" to tree.minClusterSize,
            "community_resolution" to tree.communityResolution,
            "fusion_semantic_weight" to tree.fusionSemanticWeight,
            "fusion_lexical_weight" to tree.fusionLexicalWeight,
            "fusion_lexical_gate" to tree.fusionLexicalGate,
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
