package com.autodoctree.worker.ingest

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WorkerIngestApplication

fun main(args: Array<String>) {
    runApplication<WorkerIngestApplication>(*args)
}
