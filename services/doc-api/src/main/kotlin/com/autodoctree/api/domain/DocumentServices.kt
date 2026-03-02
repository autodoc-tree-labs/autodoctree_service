package com.autodoctree.api.domain

import com.autodoctree.api.db.AttachmentRepository
import com.autodoctree.api.db.DocumentFavoriteRepository
import com.autodoctree.api.db.DocumentPersonalTopRepository
import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.PipelineStatusRow
import com.autodoctree.api.db.PipelineStatusRepository
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
    private val documentFavoriteRepository: DocumentFavoriteRepository,
    private val documentPersonalTopRepository: DocumentPersonalTopRepository,
    private val pipelineStatusRepository: PipelineStatusRepository,
    private val attachmentRepository: AttachmentRepository,
    private val documentContentMapper: DocumentContentMapper,
    private val s3StorageService: S3StorageService,
    private val outboxService: OutboxService,
    private val auditService: AuditService
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val sidebarRootLimit = 20
    private val personalTopLimit = 20
    private val libraryPageSizeMax = 100

    private data class LibraryTreeNode(
        val document: DocumentRow,
        val children: MutableList<LibraryTreeNode> = mutableListOf()
    )

    @Transactional
    fun createDocument(
        context: WorkspaceContext,
        title: String,
        bodyMarkdown: String?,
        blocksJson: JsonNode?,
        sourceType: String,
        parentDocumentId: String?
    ): Map<String, Any?> {
        requireEditor(context)
        val resolvedParentDocumentId = resolveParentDocumentId(context, parentDocumentId)
        val resolvedContent = documentContentMapper.resolveForPersist(blocksJson, bodyMarkdown)
        val document = documentRepository.create(
            workspaceId = context.workspaceId,
            title = title,
            bodyMarkdown = resolvedContent.bodyMarkdown,
            bodyText = resolvedContent.bodyText,
            blocksJson = resolvedContent.blocksJson,
            sourceType = sourceType,
            createdBy = context.userId,
            parentDocumentId = resolvedParentDocumentId
        )
        pipelineStatusRepository.create(context.workspaceId, document.id)
        outboxService.enqueue(
            workspaceId = context.workspaceId,
            documentId = document.id,
            eventType = "DocumentSaved",
            payload = mapOf(
                "document_id" to document.id,
                "source_type" to sourceType,
                "parent_document_id" to resolvedParentDocumentId
            )
        )
        return mapOf("id" to document.id)
    }

    fun getDocument(context: WorkspaceContext, documentId: String): Map<String, Any?> {
        val document = documentRepository.findByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        val status = pipelineStatusRepository.findByWorkspaceAndDocument(context.workspaceId, documentId)
            ?: throw NotFoundException()
        val attachments = attachmentRepository.listByWorkspaceAndDocument(context.workspaceId, documentId)
        val attachmentPayload = attachments.map { attachment ->
            val downloadUrl = if (attachment.status == "UPLOADED") {
                runCatching {
                    s3StorageService.presignGetObject(
                        workspaceId = context.workspaceId,
                        objectKey = attachment.objectKey,
                        expiresInSeconds = 600
                    ).url().toString()
                }.onFailure { error ->
                    logger.warn(
                        "document_attachment_presign_failed workspace_id={} document_id={} attachment_id={} error_type={}",
                        context.workspaceId,
                        documentId,
                        attachment.id,
                        error::class.java.simpleName
                    )
                }.getOrNull()
            } else {
                null
            }
            mapOf(
                "id" to attachment.id,
                "filename" to attachment.filename,
                "content_type" to attachment.contentType,
                "size" to attachment.size,
                "status" to attachment.status,
                "download_url" to downloadUrl
            )
        }
        return mapOf(
            "id" to document.id,
            "workspace_id" to document.workspaceId,
            "title" to document.title,
            "body_markdown" to (document.bodyMarkdown ?: ""),
            "blocks_json" to documentContentMapper.toResponseBlocks(document.blocksJson, document.bodyMarkdown),
            "parent_document_id" to document.parentDocumentId,
            "status" to document.status,
            "version" to document.version,
            "created_by" to document.createdBy,
            "updated_by" to document.updatedBy,
            "created_at" to document.createdAt.toString(),
            "updated_at" to document.updatedAt.toString(),
            "pipeline_status" to mapOf(
                "ingest" to status.ingestStatus.name,
                "embed" to status.embedStatus.name,
                "index" to status.indexStatus.name,
                "tree" to status.treeStatus.name,
                "failure_reason" to status.failureReason
            ),
            "attachments" to attachmentPayload
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
                "parent_document_id" to document.parentDocumentId,
                "status" to document.status,
                "created_by" to document.createdBy,
                "updated_by" to document.updatedBy,
                "created_at" to document.createdAt.toString(),
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

    fun listSidebar(context: WorkspaceContext): Map<String, Any?> {
        val documents = documentRepository.listWorkspaceDocuments(context.workspaceId)
        val roots = buildLibraryForest(documents)
        val personalTopDocumentIds = resolvePersonalTopDocumentIds(context, roots.map { it.document.id }.toSet())
        val orderedRoots = orderRootsByPersonalTop(roots, personalTopDocumentIds)
        val limitedRoots = orderedRoots.take(sidebarRootLimit)
        return mapOf(
            "items" to limitedRoots.map { toLibraryTreePayload(it) },
            "total_roots" to orderedRoots.size,
            "limit" to sidebarRootLimit,
            "has_more" to (orderedRoots.size > sidebarRootLimit),
            "personal_top_document_ids" to personalTopDocumentIds
        )
    }

    fun listLibrary(
        context: WorkspaceContext,
        query: String?,
        page: Int,
        size: Int
    ): Map<String, Any?> {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, libraryPageSizeMax)
        val documents = documentRepository.listWorkspaceDocuments(context.workspaceId)
        val allRoots = buildLibraryForest(documents)
        val personalTopDocumentIds = resolvePersonalTopDocumentIds(context, allRoots.map { it.document.id }.toSet())
        val filteredRoots = filterLibraryForest(allRoots, query)
        val orderedRoots = orderRootsByPersonalTop(filteredRoots, personalTopDocumentIds)
        val totalRoots = orderedRoots.size
        val fromIndex = (safePage * safeSize).coerceAtMost(totalRoots)
        val toIndex = (fromIndex + safeSize).coerceAtMost(totalRoots)
        val pageRoots = orderedRoots.subList(fromIndex, toIndex)
        val totalPages = if (totalRoots == 0) 0 else ((totalRoots - 1) / safeSize) + 1
        return mapOf(
            "items" to pageRoots.map { toLibraryTreePayload(it) },
            "page" to safePage,
            "size" to safeSize,
            "total_roots" to totalRoots,
            "total_pages" to totalPages,
            "has_more" to (toIndex < totalRoots),
            "personal_top_document_ids" to personalTopDocumentIds
        )
    }

    @Transactional
    fun movePersonalTop(context: WorkspaceContext, documentIds: List<String>): Map<String, Any?> {
        val selectedRootIds = normalizeDocumentIds(documentIds)
        if (selectedRootIds.isEmpty()) {
            throw BadRequestException("document_ids required")
        }
        val roots = buildLibraryForest(documentRepository.listWorkspaceDocuments(context.workspaceId))
        val rootIdSet = roots.map { it.document.id }.toSet()
        selectedRootIds.forEach { rootId ->
            if (!rootIdSet.contains(rootId)) {
                throw BadRequestException("document_ids must include only root documents")
            }
        }
        val existingRootIds = resolvePersonalTopDocumentIds(context, rootIdSet)
        val merged = (selectedRootIds + existingRootIds).distinct().take(personalTopLimit)
        documentPersonalTopRepository.replaceForWorkspaceAndUser(context.workspaceId, context.userId, merged)
        auditService.write(
            context.workspaceId,
            context.userId,
            "document.personal_top.move",
            mapOf(
                "selected_count" to selectedRootIds.size,
                "stored_count" to merged.size
            )
        )
        return mapOf(
            "items" to merged,
            "limit" to personalTopLimit,
            "total" to merged.size
        )
    }

    @Transactional
    fun bulkTrashRoots(context: WorkspaceContext, documentIds: List<String>): Map<String, Any?> {
        requireEditor(context)
        val rootIds = normalizeDocumentIds(documentIds)
        if (rootIds.isEmpty()) {
            throw BadRequestException("document_ids required")
        }

        val documentsById = documentRepository.listWorkspaceDocuments(context.workspaceId).associateBy { it.id }
        rootIds.forEach { rootId ->
            val document = documentsById[rootId] ?: throw NotFoundException()
            if (document.parentDocumentId != null) {
                throw BadRequestException("only root documents can be trashed in bulk")
            }
        }

        val deletedDocumentIds = linkedSetOf<String>()
        rootIds.forEach { rootId ->
            deletedDocumentIds.addAll(documentRepository.listSubtreeDocumentIds(context.workspaceId, rootId))
        }
        if (deletedDocumentIds.isEmpty()) {
            throw NotFoundException()
        }

        documentFavoriteRepository.removeByDocuments(context.workspaceId, deletedDocumentIds)
        documentPersonalTopRepository.removeByDocuments(context.workspaceId, deletedDocumentIds)
        documentRepository.softDeleteDocuments(context.workspaceId, deletedDocumentIds)
        deletedDocumentIds.forEach { deletedDocumentId ->
            outboxService.enqueue(
                workspaceId = context.workspaceId,
                documentId = deletedDocumentId,
                eventType = "DocumentDeleted",
                payload = mapOf("document_id" to deletedDocumentId)
            )
        }
        auditService.write(
            context.workspaceId,
            context.userId,
            "document.bulk_trash",
            mapOf(
                "root_count" to rootIds.size,
                "document_count" to deletedDocumentIds.size
            )
        )
        return mapOf(
            "deleted_root_count" to rootIds.size,
            "deleted_document_count" to deletedDocumentIds.size
        )
    }

    fun listTrash(
        context: WorkspaceContext,
        query: String?,
        page: Int,
        size: Int
    ): Map<String, Any?> {
        val items = documentRepository.listDeletedByWorkspace(
            workspaceId = context.workspaceId,
            query = query,
            page = page,
            size = size
        )
        val total = documentRepository.countDeletedByWorkspace(context.workspaceId, query)
        val payload = items.map { document ->
            val pipeline = pipelineStatusRepository.findByWorkspaceAndDocument(context.workspaceId, document.id)
            mapOf(
                "id" to document.id,
                "title" to document.title,
                "parent_document_id" to document.parentDocumentId,
                "status" to document.status,
                "created_by" to document.createdBy,
                "updated_by" to document.updatedBy,
                "created_at" to document.createdAt.toString(),
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

    fun listFavorites(context: WorkspaceContext): Map<String, Any?> {
        val favorites = documentFavoriteRepository.listByWorkspaceAndUser(context.workspaceId, context.userId)
        return mapOf(
            "items" to favorites.map { favorite ->
                mapOf(
                    "document_id" to favorite.documentId,
                    "created_at" to favorite.createdAt.toString()
                )
            },
            "total" to favorites.size
        )
    }

    @Transactional
    fun addFavorite(context: WorkspaceContext, documentId: String) {
        documentRepository.findByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        documentFavoriteRepository.add(context.workspaceId, context.userId, documentId)
        auditService.write(
            context.workspaceId,
            context.userId,
            "document.favorite.add",
            mapOf("document_id" to documentId)
        )
    }

    @Transactional
    fun removeFavorite(context: WorkspaceContext, documentId: String) {
        documentFavoriteRepository.remove(context.workspaceId, context.userId, documentId)
        auditService.write(
            context.workspaceId,
            context.userId,
            "document.favorite.remove",
            mapOf("document_id" to documentId)
        )
    }

    @Transactional
    fun moveDocument(context: WorkspaceContext, documentId: String, parentDocumentId: String?) {
        requireEditor(context)
        val document = documentRepository.findByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        val resolvedParentDocumentId = resolveMoveParentDocumentId(context, document.id, parentDocumentId)
        if (document.parentDocumentId == resolvedParentDocumentId) {
            return
        }
        documentRepository.moveParent(context.workspaceId, document.id, resolvedParentDocumentId)
        auditService.write(
            context.workspaceId,
            context.userId,
            "document.move",
            mapOf(
                "document_id" to document.id,
                "from_parent_document_id" to document.parentDocumentId,
                "to_parent_document_id" to resolvedParentDocumentId
            )
        )
    }

    @Transactional
    fun patchDocument(
        context: WorkspaceContext,
        documentId: String,
        expectedVersion: Long,
        title: String,
        bodyMarkdown: String?,
        blocksJson: JsonNode?
    ) {
        requireEditor(context)
        val resolvedContent = documentContentMapper.resolveForPersist(blocksJson, bodyMarkdown)
        documentRepository.update(
            workspaceId = context.workspaceId,
            documentId = documentId,
            expectedVersion = expectedVersion,
            title = title,
            bodyMarkdown = resolvedContent.bodyMarkdown,
            bodyText = resolvedContent.bodyText,
            blocksJson = resolvedContent.blocksJson,
            updatedBy = context.userId
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
        val subtreeDocumentIds = documentRepository.listSubtreeDocumentIds(context.workspaceId, documentId)
        if (subtreeDocumentIds.isEmpty()) {
            throw NotFoundException()
        }
        documentFavoriteRepository.removeByDocuments(context.workspaceId, subtreeDocumentIds)
        documentPersonalTopRepository.removeByDocuments(context.workspaceId, subtreeDocumentIds)
        documentRepository.softDeleteDocuments(context.workspaceId, subtreeDocumentIds)
        subtreeDocumentIds.forEach { deletedDocumentId ->
            outboxService.enqueue(
                workspaceId = context.workspaceId,
                documentId = deletedDocumentId,
                eventType = "DocumentDeleted",
                payload = mapOf("document_id" to deletedDocumentId)
            )
        }
    }

    @Transactional
    fun restoreDocument(context: WorkspaceContext, documentId: String) {
        requireEditor(context)
        val deleted = documentRepository.findDeletedByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        documentRepository.restore(context.workspaceId, documentId, status = "PROCESSING")
        pipelineStatusRepository.markRetryPendingFromStage(context.workspaceId, documentId, Stage.INGEST)
        outboxService.enqueue(
            workspaceId = context.workspaceId,
            documentId = documentId,
            eventType = "DocumentSaved",
            payload = mapOf(
                "document_id" to documentId,
                "source_type" to deleted.sourceType,
                "restored" to true
            )
        )
        auditService.write(
            context.workspaceId,
            context.userId,
            "document.restore",
            mapOf("document_id" to documentId)
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
        pipelineStatusRepository.markRetryPendingFromStage(context.workspaceId, documentId, parsedStage)
        documentRepository.updateStatus(context.workspaceId, documentId, "PROCESSING")
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

    private fun resolveParentDocumentId(context: WorkspaceContext, parentDocumentId: String?): String? {
        val normalized = parentDocumentId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parent = documentRepository.findByWorkspaceAndId(context.workspaceId, normalized)
            ?: throw BadRequestException("parent document not found")
        return parent.id
    }

    private fun resolveMoveParentDocumentId(
        context: WorkspaceContext,
        documentId: String,
        parentDocumentId: String?
    ): String? {
        val normalized = parentDocumentId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (normalized == documentId) {
            throw BadRequestException("parent document cycle")
        }
        val parent = documentRepository.findByWorkspaceAndId(context.workspaceId, normalized)
            ?: throw BadRequestException("parent document not found")
        val documents = documentRepository.listWorkspaceDocuments(context.workspaceId).associateBy { it.id }
        val seen = mutableSetOf<String>()
        var cursor: String? = parent.id
        while (cursor != null) {
            if (!seen.add(cursor)) {
                break
            }
            if (cursor == documentId) {
                throw BadRequestException("parent document cycle")
            }
            cursor = documents[cursor]?.parentDocumentId
        }
        return parent.id
    }

    private fun normalizeDocumentIds(documentIds: List<String>): List<String> {
        return documentIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun buildLibraryForest(documents: List<DocumentRow>): List<LibraryTreeNode> {
        val sorted = documents.sortedWith { left, right -> compareDocumentsByRecency(left, right) }
        val nodeById = sorted.associate { it.id to LibraryTreeNode(document = it) }
        val roots = mutableListOf<LibraryTreeNode>()
        sorted.forEach { document ->
            val node = nodeById[document.id] ?: return@forEach
            val parentId = document.parentDocumentId
            val parentNode = if (!parentId.isNullOrBlank() && parentId != document.id) nodeById[parentId] else null
            if (parentNode == null) {
                roots += node
            } else {
                parentNode.children += node
            }
        }
        fun sortRecursively(nodes: MutableList<LibraryTreeNode>) {
            nodes.sortWith { left, right -> compareDocumentsByRecency(left.document, right.document) }
            nodes.forEach { child ->
                if (child.children.isNotEmpty()) {
                    sortRecursively(child.children)
                }
            }
        }
        sortRecursively(roots)
        return roots
    }

    private fun filterLibraryForest(roots: List<LibraryTreeNode>, query: String?): List<LibraryTreeNode> {
        val normalized = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return roots
        fun filterRecursively(node: LibraryTreeNode): LibraryTreeNode? {
            val title = node.document.title.lowercase()
            val body = (node.document.bodyText ?: "").lowercase()
            val children = node.children
                .mapNotNull(::filterRecursively)
                .toMutableList()
            val matched = title.contains(normalized) || body.contains(normalized)
            return if (!matched && children.isEmpty()) {
                null
            } else {
                LibraryTreeNode(document = node.document, children = children)
            }
        }
        return roots.mapNotNull(::filterRecursively)
    }

    private fun resolvePersonalTopDocumentIds(
        context: WorkspaceContext,
        validRootIds: Set<String>
    ): List<String> {
        return documentPersonalTopRepository
            .listByWorkspaceAndUser(context.workspaceId, context.userId)
            .map { it.documentId }
            .filter { validRootIds.contains(it) }
            .distinct()
            .take(personalTopLimit)
    }

    private fun orderRootsByPersonalTop(
        roots: List<LibraryTreeNode>,
        personalTopDocumentIds: List<String>
    ): List<LibraryTreeNode> {
        if (roots.isEmpty()) {
            return emptyList()
        }
        val rootById = roots.associateBy { it.document.id }
        val pinned = personalTopDocumentIds.mapNotNull { rootById[it] }
        val pinnedSet = pinned.map { it.document.id }.toSet()
        val unpinned = roots.filterNot { pinnedSet.contains(it.document.id) }
        return pinned + unpinned
    }

    private fun compareDocumentsByRecency(left: DocumentRow, right: DocumentRow): Int {
        val updatedDiff = right.updatedAt.compareTo(left.updatedAt)
        if (updatedDiff != 0) {
            return updatedDiff
        }
        return left.title.compareTo(right.title)
    }

    private fun toLibraryTreePayload(node: LibraryTreeNode): Map<String, Any?> {
        return mapOf(
            "id" to node.document.id,
            "title" to node.document.title,
            "parent_document_id" to node.document.parentDocumentId,
            "status" to node.document.status,
            "created_by" to node.document.createdBy,
            "updated_by" to node.document.updatedBy,
            "created_at" to node.document.createdAt.toString(),
            "updated_at" to node.document.updatedAt.toString(),
            "children" to node.children.map { toLibraryTreePayload(it) }
        )
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

@Service
class SearchService(
    private val searchDocumentRepository: com.autodoctree.api.db.SearchDocumentRepository,
    private val paletteHistoryRepository: com.autodoctree.api.db.PaletteHistoryRepository
) {
    fun search(
        context: WorkspaceContext,
        q: String,
        page: Int,
        size: Int,
        mode: String,
        sort: String,
        titleOnly: Boolean,
        createdBy: String?,
        updatedBy: String?,
        fromDate: String?,
        toDate: String?,
        scope: String,
        scopePageId: String?,
        debug: Boolean
    ): Map<String, Any?> {
        if (q.isBlank()) {
            throw BadRequestException("q is required")
        }
        val normalizedMode = if (mode.equals("hybrid", ignoreCase = true)) "hybrid" else "bm25"
        val sortSql = when (sort.lowercase()) {
            "updated_at_asc" -> "d.updated_at ASC"
            "updated_at_desc" -> "d.updated_at DESC"
            "created_at_asc" -> "d.created_at ASC"
            "created_at_desc" -> "d.created_at DESC"
            else -> "d.updated_at DESC"
        }
        val from = fromDate?.takeIf { it.isNotBlank() }?.let { java.time.LocalDate.parse(it).atStartOfDay() }
        val to = toDate?.takeIf { it.isNotBlank() }?.let { java.time.LocalDate.parse(it).plusDays(1).atStartOfDay().minusSeconds(1) }
        val scopedPage = if (scope.equals("page_subtree", ignoreCase = true)) scopePageId else null

        val docs = searchDocumentRepository.search(
            workspaceId = context.workspaceId,
            userId = context.userId,
            query = q,
            titleOnly = titleOnly,
            createdBy = createdBy,
            updatedBy = updatedBy,
            fromDate = from,
            toDate = to,
            scopePageId = scopedPage,
            sortSql = sortSql,
            size = size,
            offset = page * size
        )

        val items = docs.map {
            val baseScore = if (normalizedMode == "hybrid") 1.2 else 1.0
            mapOf(
                "document_id" to it.id,
                "title" to it.title,
                "score" to baseScore,
                "breadcrumb" to listOfNotNull(it.parentDocumentId, it.id)
            )
        }

        val response = mutableMapOf<String, Any?>("items" to items)
        if (debug && context.role == com.autodoctree.common.Role.OWNER) {
            response["debug"] = mapOf(
                "hitsCount" to items.size,
                "usedMode" to normalizedMode,
                "appliedFilters" to mapOf(
                    "titleOnly" to titleOnly,
                    "createdBy" to createdBy,
                    "updatedBy" to updatedBy,
                    "fromDate" to fromDate,
                    "toDate" to toDate,
                    "scope" to scope,
                    "scopePageId" to scopedPage,
                    "sort" to sort
                )
            )
        }
        return response
    }

    fun recordHistory(context: WorkspaceContext, eventType: String, queryText: String?, documentId: String?, commandKey: String?) {
        val trimmed = queryText?.trim()?.take(256)
        paletteHistoryRepository.insert(
            workspaceId = context.workspaceId,
            userId = context.userId,
            eventType = eventType.uppercase(),
            queryText = trimmed,
            documentId = documentId,
            commandKey = commandKey
        )
    }

        fun listHistory(context: WorkspaceContext, limit: Int): Map<String, Any?> {
            val rows = paletteHistoryRepository.list(context.workspaceId, context.userId, limit)
            return mapOf(
                "items" to rows.map {
                    mapOf(
                        "id" to it.id,
                        "event_type" to it.eventType,
                        "query_text" to it.queryText,
                        "document_id" to it.documentId,
                    "command_key" to it.commandKey,
                        "created_at" to it.createdAt.toString()
                    )
                }
            )
    }
}
