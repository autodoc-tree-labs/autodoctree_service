package com.autodoctree.api.domain

import com.autodoctree.api.config.WorkerProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

    @Test
    fun `status shows queued running and idle transitions`() {
        val queue = RebuildDebounceQueue(
            workerProperties = WorkerProperties(
                pollIntervalMs = 1000,
                maxRetries = 3,
                debounceWindowSeconds = 1
            )
        )

        assertEquals("IDLE", queue.status("ws-a").status)

        queue.request("ws-a", "SAVE")
        val queued = queue.status("ws-a")
        assertEquals("QUEUED", queued.status)
        assertEquals(1, queued.pendingCount)

        queue.markRunning("ws-a")
        val running = queue.status("ws-a")
        assertEquals("RUNNING", running.status)
        assertEquals(1, running.pendingCount)
        assertNotNull(running.runningSince)

        queue.markIdle("ws-a")
        val idle = queue.status("ws-a")
        assertEquals("QUEUED", idle.status)
        assertEquals(1, idle.pendingCount)

        Thread.sleep(1100)
        queue.dequeueDue()
        assertEquals("IDLE", queue.status("ws-a").status)
    }
}
