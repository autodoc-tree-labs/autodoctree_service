package com.autodoctree.api.storage

import com.autodoctree.api.config.StorageProperties
import com.autodoctree.api.infra.BadRequestException
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration

@Service
class S3StorageService(
    private val s3Presigner: S3Presigner,
    private val s3Client: S3Client,
    private val storageProperties: StorageProperties,
    meterRegistry: MeterRegistry
) {
    private val namespaceViolationCounter = meterRegistry.counter("security.storage_namespace_violation_total")

    fun presignPutObject(
        workspaceId: String,
        objectKey: String,
        contentType: String,
        expiresInSeconds: Long
    ): PresignedPutObjectRequest {
        assertWorkspaceObjectKey(workspaceId, objectKey)
        val putObjectRequest = PutObjectRequest.builder()
            .bucket(storageProperties.bucket)
            .key(objectKey)
            .contentType(contentType)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofSeconds(expiresInSeconds))
            .putObjectRequest(putObjectRequest)
            .build()

        return s3Presigner.presignPutObject(presignRequest)
    }

    fun presignGetObject(
        workspaceId: String,
        objectKey: String,
        expiresInSeconds: Long
    ): PresignedGetObjectRequest {
        assertWorkspaceObjectKey(workspaceId, objectKey)
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(storageProperties.bucket)
            .key(objectKey)
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofSeconds(expiresInSeconds))
            .getObjectRequest(getObjectRequest)
            .build()
        return s3Presigner.presignGetObject(presignRequest)
    }

    fun readObjectBytes(workspaceId: String, objectKey: String): ByteArray {
        assertWorkspaceObjectKey(workspaceId, objectKey)
        val request = GetObjectRequest.builder()
            .bucket(storageProperties.bucket)
            .key(objectKey)
            .build()

        val stream: ResponseInputStream<GetObjectResponse> = s3Client.getObject(request)
        stream.use {
            return it.readAllBytes()
        }
    }

    fun assertWorkspaceObjectKey(workspaceId: String, objectKey: String) {
        val normalizedWorkspaceId = workspaceId.trim()
        val expectedPrefix = "workspaces/$normalizedWorkspaceId/"
        if (normalizedWorkspaceId.isBlank() || !objectKey.startsWith(expectedPrefix)) {
            namespaceViolationCounter.increment()
            throw BadRequestException("object key must be scoped to workspace")
        }
    }
}
