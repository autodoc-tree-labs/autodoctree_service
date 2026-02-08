package com.autodoctree.worker.tree

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WorkerTreeApplication

fun main(args: Array<String>) {
    runApplication<WorkerTreeApplication>(*args)
}
