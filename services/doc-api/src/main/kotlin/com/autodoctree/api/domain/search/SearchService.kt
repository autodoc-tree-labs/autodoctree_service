package com.autodoctree.api.domain.search

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

@Service
class SearchService(
    private val searchDocumentRepository: com.autodoctree.api.db.search.SearchDocumentRepository,
    private val paletteHistoryRepository: com.autodoctree.api.db.admin.PaletteHistoryRepository
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
