package com.autodoctree.api.domain

import com.autodoctree.api.db.AttachmentRepository
import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.PipelineStatusRow
import com.autodoctree.api.db.PipelineStatusRepository
import com.autodoctree.api.infra.BadRequestException
import com.autodoctree.api.infra.LogSanitizer
import com.autodoctree.api.infra.NotFoundException
import com.autodoctree.api.infra.requireEditor
import com.autodoctree.api.search.SearchSpec
import com.autodoctree.api.search.TenantSearchClient
import com.autodoctree.api.storage.S3StorageService
import com.autodoctree.api.tenant.WorkspaceContext
import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AuditService(
    private val auditLogRepository: com.autodoctree.api.db.AuditLogRepository,
    private val objectMapper: ObjectMapper,
    private val logSanitizer: LogSanitizer,
    meterRegistry: MeterRegistry
) {
    private val auditEventCounter = meterRegistry.counter("audit_event_total")

    fun write(workspaceId: String, actorUserId: String, action: String, payload: Map<String, Any?>) {
        val sanitizedPayload = logSanitizer.sanitize(payload)
        auditLogRepository.insert(
            workspaceId,
            actorUserId,
            action,
            objectMapper.writeValueAsString(sanitizedPayload)
        )
        auditEventCounter.increment()
    }
}

@Service
class OutboxService(
    private val outboxRepository: com.autodoctree.api.db.OutboxRepository,
    private val objectMapper: ObjectMapper
) {
    fun enqueue(workspaceId: String, documentId: String?, eventType: String, payload: Map<String, Any?>): String {
        return outboxRepository.insert(workspaceId, documentId, eventType, objectMapper.writeValueAsString(payload))
    }
}

@Service
class DocumentService(
    private val documentRepository: DocumentRepository,
    private val pipelineStatusRepository: PipelineStatusRepository,
    private val attachmentRepository: AttachmentRepository,
    private val outboxService: OutboxService,
    private val auditService: AuditService
) {

    @Transactional
    fun createDocument(
        context: WorkspaceContext,
        title: String,
        bodyMarkdown: String?,
        sourceType: String
    ): Map<String, Any?> {
        requireEditor(context)
        val document = documentRepository.create(
            workspaceId = context.workspaceId,
            title = title,
            bodyMarkdown = bodyMarkdown,
            bodyText = bodyMarkdown,
            sourceType = sourceType,
            createdBy = context.userId
        )
        pipelineStatusRepository.create(context.workspaceId, document.id)
        outboxService.enqueue(
            workspaceId = context.workspaceId,
            documentId = document.id,
            eventType = "DocumentSaved",
            payload = mapOf(
                "document_id" to document.id,
                "source_type" to sourceType
            )
        )
        return mapOf("id" to document.id)
    }

    fun getDocument(context: WorkspaceContext, documentId: String): Map<String, Any?> {
        val document = documentRepository.findByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        val status = pipelineStatusRepository.findByWorkspaceAndDocument(context.workspaceId, documentId)
            ?: throw NotFoundException()
        val attachments = attachmentRepository.listByWorkspaceAndDocument(context.workspaceId, documentId)
        return mapOf(
            "id" to document.id,
            "workspace_id" to document.workspaceId,
            "title" to document.title,
            "body_markdown" to (document.bodyMarkdown ?: ""),
            "status" to document.status,
            "version" to document.version,
            "updated_at" to document.updatedAt.toString(),
            "pipeline_status" to mapOf(
                "ingest" to status.ingestStatus.name,
                "embed" to status.embedStatus.name,
                "index" to status.indexStatus.name,
                "tree" to status.treeStatus.name,
                "failure_reason" to status.failureReason
            ),
            "attachments" to attachments.map {
                mapOf(
                    "id" to it.id,
                    "content_type" to it.contentType,
                    "size" to it.size
                )
            }
        )
    }

    fun listDocuments(
        context: WorkspaceContext,
        status: String?,
        query: String?,
        page: Int,
        size: Int
    ): Map<String, Any?> {
        val items = documentRepository.listByWorkspace(
            workspaceId = context.workspaceId,
            status = status,
            query = query,
            page = page,
            size = size
        )
        val total = documentRepository.countByWorkspace(context.workspaceId, status, query)
        val payload = items.map { document ->
            val pipeline = pipelineStatusRepository.findByWorkspaceAndDocument(context.workspaceId, document.id)
            mapOf(
                "id" to document.id,
                "title" to document.title,
                "status" to document.status,
                "updated_at" to document.updatedAt.toString(),
                "pipeline_status" to mapOf(
                    "ingest" to (pipeline?.ingestStatus?.name ?: "PENDING"),
                    "embed" to (pipeline?.embedStatus?.name ?: "PENDING"),
                    "index" to (pipeline?.indexStatus?.name ?: "PENDING"),
                    "tree" to (pipeline?.treeStatus?.name ?: "PENDING")
                ),
                "attachments" to attachmentRepository.listByWorkspaceAndDocument(context.workspaceId, document.id).map {
                    mapOf(
                        "id" to it.id,
                        "content_type" to it.contentType,
                        "size" to it.size
                    )
                }
            )
        }
        return mapOf(
            "items" to payload,
            "page" to page,
            "size" to size,
            "total" to total
        )
    }

    @Transactional
    fun patchDocument(
        context: WorkspaceContext,
        documentId: String,
        expectedVersion: Long,
        title: String,
        bodyMarkdown: String?
    ) {
        requireEditor(context)
        documentRepository.update(
            workspaceId = context.workspaceId,
            documentId = documentId,
            expectedVersion = expectedVersion,
            title = title,
            bodyMarkdown = bodyMarkdown
        )
        outboxService.enqueue(
            workspaceId = context.workspaceId,
            documentId = documentId,
            eventType = "DocumentUpdated",
            payload = mapOf(
                "document_id" to documentId,
                "updated_at" to LocalDateTime.now().toString()
            )
        )
    }

    @Transactional
    fun deleteDocument(context: WorkspaceContext, documentId: String) {
        requireEditor(context)
        documentRepository.findByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        documentRepository.softDelete(context.workspaceId, documentId)
        outboxService.enqueue(
            workspaceId = context.workspaceId,
            documentId = documentId,
            eventType = "DocumentDeleted",
            payload = mapOf("document_id" to documentId)
        )
    }

    @Transactional
    fun retryPipelineStage(context: WorkspaceContext, documentId: String, stage: String) {
        requireEditor(context)
        documentRepository.findByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        val pipeline = pipelineStatusRepository.findByWorkspaceAndDocument(context.workspaceId, documentId)
            ?: throw NotFoundException()
        val parsedStage = parseStage(stage)
        if (stageStatus(pipeline, parsedStage) != StageStatus.FAILED) {
            throw BadRequestException("stage must be FAILED to retry")
        }
        val payload = mapOf(
            "document_id" to documentId,
            "stage" to parsedStage.name
        )
        outboxService.enqueue(
            workspaceId = context.workspaceId,
            documentId = documentId,
            eventType = "StageRetry",
            payload = payload
        )
        auditService.write(
            context.workspaceId,
            context.userId,
            "document.pipeline.retry",
            payload
        )
    }

    private fun parseStage(raw: String): Stage {
        return try {
            Stage.valueOf(raw.trim().uppercase())
        } catch (_: IllegalArgumentException) {
            throw BadRequestException("unsupported stage")
        }
    }

    private fun stageStatus(pipeline: PipelineStatusRow, stage: Stage): StageStatus {
        return when (stage) {
            Stage.INGEST -> pipeline.ingestStatus
            Stage.EMBED -> pipeline.embedStatus
            Stage.INDEX -> pipeline.indexStatus
            Stage.TREE -> pipeline.treeStatus
        }
    }
}

@Service
class AttachmentService(
    private val documentRepository: DocumentRepository,
    private val attachmentRepository: AttachmentRepository,
    private val s3StorageService: S3StorageService,
    private val outboxService: OutboxService,
    private val auditService: AuditService
) {
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
}

@Service
class SearchService(
    private val tenantSearchClient: TenantSearchClient
) {
    fun search(context: WorkspaceContext, q: String, page: Int, size: Int): Map<String, Any?> {
        if (q.isBlank()) {
            throw BadRequestException("q is required")
        }
        val hits = tenantSearchClient.search(
            workspaceId = context.workspaceId,
            spec = SearchSpec(q, page, size)
        )
        return mapOf(
            "items" to hits.map {
                mapOf(
                    "document_id" to it.documentId,
                    "title" to it.title,
                    "score" to it.score
                )
            }
        )
    }
}
