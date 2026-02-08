package com.autodoctree.worker.embed

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WorkerEmbedApplication

fun main(args: Array<String>) {
    runApplication<WorkerEmbedApplication>(*args)
}
