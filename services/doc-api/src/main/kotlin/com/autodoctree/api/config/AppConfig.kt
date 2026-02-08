package com.autodoctree.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

@ConfigurationProperties(prefix = "auth")
data class AuthProperties(
    val jwtSecret: String,
    val accessTokenTtlSeconds: Long,
    val refreshTokenTtlSeconds: Long
)

@ConfigurationProperties(prefix = "storage")
data class StorageProperties(
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    val region: String
)

@ConfigurationProperties(prefix = "worker")
data class WorkerProperties(
    val pollIntervalMs: Long,
    val maxRetries: Int,
    val debounceWindowSeconds: Long
)

@ConfigurationProperties(prefix = "feature")
data class FeatureFlags(
    val autoTree: Boolean,
    val explain: Boolean,
    val hybridSearch: Boolean
)

@ConfigurationProperties(prefix = "security")
data class SecurityFlags(
    val osTenantAssert: Boolean
)

@Configuration
class AppConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper().apply {
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
    }

    @Bean
    fun s3Client(storageProperties: StorageProperties): S3Client {
        val credentials = AwsBasicCredentials.create(storageProperties.accessKey, storageProperties.secretKey)
        return S3Client.builder()
            .endpointOverride(URI.create(storageProperties.endpoint))
            .region(Region.of(storageProperties.region))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .forcePathStyle(true)
            .build()
    }

    @Bean
    fun s3Presigner(storageProperties: StorageProperties): S3Presigner {
        val credentials = AwsBasicCredentials.create(storageProperties.accessKey, storageProperties.secretKey)
        return S3Presigner.builder()
            .endpointOverride(URI.create(storageProperties.endpoint))
            .region(Region.of(storageProperties.region))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build()
            )
            .build()
    }
}
