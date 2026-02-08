package com.autodoctree.worker.index

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WorkerIndexApplication

fun main(args: Array<String>) {
    runApplication<WorkerIndexApplication>(*args)
}
