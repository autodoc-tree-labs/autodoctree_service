package com.autodoctree.api.config

import com.autodoctree.api.domain.SeedDataService
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SeedRunner {

    @Bean
    fun seedDataRunner(seedDataService: SeedDataService): ApplicationRunner = ApplicationRunner {
        seedDataService.seedIfNeeded()
    }
}
