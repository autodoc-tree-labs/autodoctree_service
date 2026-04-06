package com.autodoctree.api.domain.attachment

import com.autodoctree.api.db.document.AttachmentRepository
import com.autodoctree.api.db.document.DocumentFavoriteRepository
import com.autodoctree.api.db.document.DocumentPersonalTopRepository
import com.autodoctree.api.db.document.DocumentRepository
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.PipelineStatusRow
import com.autodoctree.api.db.pipeline.PipelineStatusRepository
import com.autodoctree.api.infra.BadRequestException
import com.autodoctree.api.infra.LogSanitizer
import com.autodoctree.api.infra.NotFoundException
import com.autodoctree.api.infra.requireEditor
import com.autodoctree.api.storage.S3StorageService
import com.autodoctree.api.tenant.WorkspaceContext
import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import com.autodoctree.api.domain.document.AuditService
import com.autodoctree.api.domain.document.OutboxService

@Service
class AttachmentService(
    private val documentRepository: DocumentRepository,
    private val attachmentRepository: AttachmentRepository,
    private val s3StorageService: S3StorageService,
    private val outboxService: OutboxService,
    private val auditService: AuditService
) {
    private val maxAttachmentSizeBytes = 50L * 1024L * 1024L
    private val allowedExactContentTypes = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/plain",
        "text/markdown",
        "text/csv",
        "application/octet-stream"
    )

    @Transactional
    fun presign(
        context: WorkspaceContext,
        documentId: String,
        filename: String,
        contentType: String,
        size: Long,
        checksumSha256: String?
    ): Map<String, Any?> {
        requireEditor(context)
        val document = documentRepository.findByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        if (size <= 0) {
            throw BadRequestException("size must be positive")
        }
        if (size > maxAttachmentSizeBytes) {
            throw BadRequestException("size exceeds maximum allowed bytes")
        }
        if (!isAllowedContentType(contentType)) {
            throw BadRequestException("unsupported content_type")
        }
        val attachment = attachmentRepository.create(
            workspaceId = context.workspaceId,
            documentId = document.id,
            filename = filename,
            contentType = contentType,
            size = size,
            objectKey = "workspaces/${context.workspaceId}/attachments/${document.id}/${System.currentTimeMillis()}_${sanitizeFilename(filename)}",
            checksumSha256 = checksumSha256
        )
        val presigned = s3StorageService.presignPutObject(
            workspaceId = context.workspaceId,
            objectKey = attachment.objectKey,
            contentType = contentType,
            expiresInSeconds = 900
        )
        auditService.write(
            context.workspaceId,
            context.userId,
            "attachment.presign_issued",
            mapOf(
                "document_id" to documentId,
                "attachment_id" to attachment.id,
                "content_type" to contentType,
                "size" to size
            )
        )
        return mapOf(
            "attachment_id" to attachment.id,
            "upload_url" to presigned.url().toString(),
            "expires_in_seconds" to 900
        )
    }

    @Transactional
    fun complete(context: WorkspaceContext, attachmentId: String) {
        requireEditor(context)
        val attachment = attachmentRepository.findByWorkspaceAndId(context.workspaceId, attachmentId) ?: throw NotFoundException()
        s3StorageService.assertWorkspaceObjectKey(context.workspaceId, attachment.objectKey)
        attachmentRepository.updateCompleted(context.workspaceId, attachmentId)
        outboxService.enqueue(
            workspaceId = context.workspaceId,
            documentId = attachment.documentId,
            eventType = "AttachmentUploaded",
            payload = mapOf(
                "attachment_id" to attachmentId,
                "document_id" to attachment.documentId,
                "object_key" to attachment.objectKey,
                "content_type" to attachment.contentType
            )
        )
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun isAllowedContentType(contentType: String): Boolean {
        val normalized = contentType.trim().lowercase()
        if (normalized.startsWith("image/")) {
            return true
        }
        return normalized in allowedExactContentTypes
    }
}
