package com.autodoctree.api.domain

import com.autodoctree.api.db.TreeNodeRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TreeSnapshotMetricsTest {
    @Test
    fun `computeNodeRenameCount ignores root and counts label churn`() {
        val now = LocalDateTime.now()
        val activeNodes = listOf(
            TreeNodeRow(
                id = "root",
                workspaceId = "ws",
                snapshotId = "snap",
                parentId = null,
                label = "AutoDoc",
                depth = 0,
                locked = false,
                createdAt = now
            ),
            TreeNodeRow(
                id = "node-a",
                workspaceId = "ws",
                snapshotId = "snap",
                parentId = "root",
                label = "alpha",
                depth = 1,
                locked = false,
                createdAt = now
            ),
            TreeNodeRow(
                id = "node-b",
                workspaceId = "ws",
                snapshotId = "snap",
                parentId = "root",
                label = "beta",
                depth = 1,
                locked = false,
                createdAt = now
            )
        )

        val newLabels = setOf("alpha", "gamma")

        val result = TreeSnapshotMetrics.computeNodeRenameCount(activeNodes, newLabels)

        assertEquals(2, result)
    }
}
