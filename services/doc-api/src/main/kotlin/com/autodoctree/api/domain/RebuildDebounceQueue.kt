package com.autodoctree.api.domain

import com.autodoctree.api.config.WorkerProperties
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class PendingRebuild(
    val workspaceId: String,
    var firstRequestedAt: Instant,
    var lastRequestedAt: Instant,
    var triggerCount: Int,
    val reasons: MutableSet<String>
)

@Service
class RebuildDebounceQueue(
    private val workerProperties: WorkerProperties
) {
    private val pendingByWorkspace = ConcurrentHashMap<String, PendingRebuild>()

    fun request(workspaceId: String, reason: String) {
        val now = Instant.now()
        pendingByWorkspace.compute(workspaceId) { _, current ->
            if (current == null) {
                PendingRebuild(
                    workspaceId = workspaceId,
                    firstRequestedAt = now,
                    lastRequestedAt = now,
                    triggerCount = 1,
                    reasons = mutableSetOf(reason)
                )
            } else {
                current.lastRequestedAt = now
                current.triggerCount += 1
                current.reasons += reason
                current
            }
        }
    }

    fun dequeueDue(): List<PendingRebuild> {
        val now = Instant.now()
        val ready = mutableListOf<PendingRebuild>()
        pendingByWorkspace.entries.forEach { entry ->
            val duration = Duration.between(entry.value.lastRequestedAt, now).seconds
            if (duration >= workerProperties.debounceWindowSeconds) {
                val removed = pendingByWorkspace.remove(entry.key)
                if (removed != null) {
                    ready += removed
                }
            }
        }
        return ready
    }

    fun pendingCount(workspaceId: String): Int {
        return pendingByWorkspace[workspaceId]?.triggerCount ?: 0
    }
}
