package com.autodoctree.api.domain.tree

import com.autodoctree.api.db.TreeNodeRow

object TreeSnapshotMetrics {
    fun computeNodeRenameCount(activeNodes: List<TreeNodeRow>, newLabels: Set<String>): Int {
        val activeLabels = activeNodes
            .asSequence()
            .filter { it.depth > 0 }
            .map { it.label }
            .toSet()
        if (activeLabels.isEmpty() && newLabels.isEmpty()) {
            return 0
        }
        val removed = activeLabels - newLabels
        val added = newLabels - activeLabels
        return removed.size + added.size
    }
}
