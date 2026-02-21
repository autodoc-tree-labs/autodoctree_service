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

data class RebuildQueueStatus(
    val status: String,
    val pendingCount: Int,
    val runningSince: Instant?
)

@Service
class RebuildDebounceQueue(
    private val workerProperties: WorkerProperties
) {
    private val pendingByWorkspace = ConcurrentHashMap<String, PendingRebuild>()
    private val runningSinceByWorkspace = ConcurrentHashMap<String, Instant>()

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

    fun markRunning(workspaceId: String) {
        runningSinceByWorkspace[workspaceId] = Instant.now()
    }

    fun markIdle(workspaceId: String) {
        runningSinceByWorkspace.remove(workspaceId)
    }

    fun status(workspaceId: String): RebuildQueueStatus {
        val pendingCount = pendingCount(workspaceId)
        val runningSince = runningSinceByWorkspace[workspaceId]
        val status = when {
            runningSince != null -> "RUNNING"
            pendingCount > 0 -> "QUEUED"
            else -> "IDLE"
        }
        return RebuildQueueStatus(
            status = status,
            pendingCount = pendingCount,
            runningSince = runningSince
        )
    }
}
