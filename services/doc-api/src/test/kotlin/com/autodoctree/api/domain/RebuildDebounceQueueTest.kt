package com.autodoctree.api.domain

import com.autodoctree.api.config.WorkerProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RebuildDebounceQueueTest {

    @Test
    fun `coalesces multiple requests into one due rebuild`() {
        val queue = RebuildDebounceQueue(
            workerProperties = WorkerProperties(
                pollIntervalMs = 1000,
                maxRetries = 3,
                debounceWindowSeconds = 1
            )
        )

        queue.request("ws-a", "SAVE")
        queue.request("ws-a", "RENAME")
        queue.request("ws-a", "UPLOAD")

        assertEquals(3, queue.pendingCount("ws-a"))
        assertTrue(queue.dequeueDue().isEmpty())

        Thread.sleep(1100)
        val due = queue.dequeueDue()

        assertEquals(1, due.size)
        assertEquals("ws-a", due.first().workspaceId)
        assertEquals(3, due.first().triggerCount)
        assertEquals(setOf("SAVE", "RENAME", "UPLOAD"), due.first().reasons)
    }
}
