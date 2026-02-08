package com.autodoctree.api.storage

import com.autodoctree.api.config.StorageProperties
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration

@Service
class S3StorageService(
    private val s3Presigner: S3Presigner,
    private val s3Client: S3Client,
    private val storageProperties: StorageProperties
) {

    fun presignPutObject(
        objectKey: String,
        contentType: String,
        expiresInSeconds: Long
    ): PresignedPutObjectRequest {
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

    fun readObjectBytes(objectKey: String): ByteArray {
        val request = GetObjectRequest.builder()
            .bucket(storageProperties.bucket)
            .key(objectKey)
            .build()

        val stream: ResponseInputStream<GetObjectResponse> = s3Client.getObject(request)
        stream.use {
            return it.readAllBytes()
        }
    }
}
