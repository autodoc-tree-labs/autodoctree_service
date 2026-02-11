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

@ConfigurationProperties(prefix = "search")
data class SearchProperties(
    val backend: String,
    val opensearchUrl: String,
    val username: String?,
    val password: String?,
    val indexAlias: String,
    val templateName: String
)

@ConfigurationProperties(prefix = "feature")
data class FeatureFlags(
    val autoTree: Boolean,
    val explain: Boolean,
    val hybridSearch: Boolean,
    val embeddingOllama: Boolean,
    val labelQualityFilter: Boolean,
    val communityClustering: Boolean,
    val noriTokenizer: Boolean,
    val feedbackRoutingV2: Boolean,
    val userRulesV1: Boolean,
    val adminTreeDebug: Boolean,
    val llmLabeling: Boolean = false,
    val llmExplain: Boolean = false,
    val tfidfLabelerFallback: Boolean = false
)

@ConfigurationProperties(prefix = "security")
data class SecurityFlags(
    val osTenantAssert: Boolean
)

@ConfigurationProperties(prefix = "tree")
data class TreeProperties(
    val neighborTopK: Int,
    val neighborMinSimilarity: Double,
    val neighborNormalize: Boolean,
    val neighborMutualKnnRequired: Boolean = true,
    val neighborSnnThreshold: Double = 0.12,
    val neighborEdgeBudget: Int = 6,
    val maxClusterSize: Int,
    val minClusterSize: Int,
    val communityResolution: Double,
    val personalizationDecay: Double,
    val personalizationMinScore: Double,
    val fusionSemanticWeight: Double,
    val fusionLexicalWeight: Double,
    val fusionLexicalGate: Double,
    val embeddingDocumentWeight: Double = 0.65,
    val embeddingSummaryWeight: Double = 0.25,
    val embeddingSectionWeight: Double = 0.10,
    val otherClusterScoreThreshold: Double
)

@ConfigurationProperties(prefix = "embedding")
data class EmbeddingProperties(
    val provider: String,
    val input: EmbeddingInputProperties,
    val ollama: OllamaEmbeddingProperties
)

data class EmbeddingInputProperties(
    val maxChars: Int,
    val headChars: Int,
    val tailChars: Int,
    val sectionHeadingLimit: Int,
    val sectionCountLimit: Int
)

data class OllamaEmbeddingProperties(
    val baseUrl: String,
    val model: String,
    val timeoutMs: Long,
    val batchSize: Int,
    val maxRetries: Int,
    val retryBackoffMs: Long,
    val circuitFailureThreshold: Int,
    val circuitOpenMs: Long
)

@ConfigurationProperties(prefix = "llm")
data class LlmProperties(
    val provider: String,
    val ollama: OllamaLlmProperties
)

data class OllamaLlmProperties(
    val baseUrl: String,
    val model: String,
    val timeoutMs: Long,
    val maxRetries: Int,
    val retryBackoffMs: Long,
    val circuitFailureThreshold: Int,
    val circuitOpenMs: Long
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
