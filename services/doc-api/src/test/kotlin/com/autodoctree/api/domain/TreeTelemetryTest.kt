package com.autodoctree.api.domain

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TreeTelemetryTest {

    private val registry = SimpleMeterRegistry()
    private val telemetry = TreeTelemetry(registry)

    @Test
    fun `stage payload keeps stable schema`() {
        val payload = telemetry.buildStageLogPayload(
            workspaceId = "ws-test",
            stageLog = TreeStageLog(
                stage = "graph",
                durationMs = 12.345,
                details = mapOf("edge_count" to 7)
            )
        )

        assertEquals("tree_rebuild_stage", payload["event"])
        assertEquals("ws-test", payload["workspace_id"])
        assertEquals("graph", payload["stage"])
        assertTrue(payload.containsKey("duration_ms"))
        assertTrue(payload.containsKey("trace_id"))
        assertTrue(payload.containsKey("request_id"))
        assertEquals(mapOf("edge_count" to 7), payload["details"])
    }

    @Test
    fun `summary payload keeps stable schema`() {
        val payload = telemetry.buildSummaryPayload(
            workspaceId = "ws-test",
            snapshotId = "snap-1",
            documentCount = 10,
            status = "ACTIVE",
            movedRatio = 0.2,
            churnRatio = 0.2,
            unsortedRatio = 0.1,
            graphStats = NeighborBuildStats(edgeCount = 20, filteredEdgeCount = 4, averageSimilarity = 0.8),
            stageLogs = listOf(TreeStageLog(stage = "graph", durationMs = 10.0))
        )

        assertEquals("tree_rebuild_summary", payload["event"])
        assertEquals("ws-test", payload["workspace_id"])
        assertEquals("snap-1", payload["snapshot_id"])
        assertEquals("ACTIVE", payload["status"])
        assertEquals(10, payload["document_count"])
        assertEquals(20, payload["edge_count"])
        assertEquals(4, payload["filtered_edge_count"])
        assertEquals(1, payload["stage_count"])
        assertTrue(payload.containsKey("stage_durations_ms"))
    }
}
