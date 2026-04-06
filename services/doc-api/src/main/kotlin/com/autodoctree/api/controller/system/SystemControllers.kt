package com.autodoctree.api.controller.system

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class HealthController {
    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf("status" to "OK")
}

@RestController
class MetricsController(
    private val meterRegistry: MeterRegistry
) {
    @GetMapping("/metrics")
    fun metrics(): Map<String, Any> {
        val names = meterRegistry.meters.map { it.id.name }.distinct().sorted()
        return mapOf("meters" to names)
    }
}
