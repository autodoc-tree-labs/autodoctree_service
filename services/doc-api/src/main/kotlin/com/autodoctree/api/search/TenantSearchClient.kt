package com.autodoctree.api.search

import com.autodoctree.api.config.SecurityFlags
import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.infra.BadRequestException
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

data class SearchSpec(
    val query: String,
    val page: Int,
    val size: Int
)

data class SearchHit(
    val documentId: String,
    val title: String,
    val score: Double
)

interface TenantSearchClient {
    fun search(workspaceId: String, spec: SearchSpec): List<SearchHit>
    fun upsert(workspaceId: String, documentId: String)
    fun delete(workspaceId: String, documentId: String)
}

@Component
class DatabaseTenantSearchClient(
    private val documentRepository: DocumentRepository,
    private val securityFlags: SecurityFlags,
    meterRegistry: MeterRegistry
) : TenantSearchClient {

    private val missingFilterCounter = meterRegistry.counter("security.os_missing_tenant_filter_total")

    override fun search(workspaceId: String, spec: SearchSpec): List<SearchHit> {
        ensureWorkspaceScope(workspaceId)
        val docs = documentRepository.searchByWorkspace(
            workspaceId = workspaceId,
            query = spec.query,
            size = spec.size,
            offset = spec.page * spec.size
        )
        return docs.map {
            val score = scoreDocument(spec.query, it.title, it.bodyText ?: "")
            SearchHit(
                documentId = it.id,
                title = it.title,
                score = score
            )
        }.sortedByDescending { it.score }
    }

    override fun upsert(workspaceId: String, documentId: String) {
        ensureWorkspaceScope(workspaceId)
    }

    override fun delete(workspaceId: String, documentId: String) {
        ensureWorkspaceScope(workspaceId)
    }

    private fun ensureWorkspaceScope(workspaceId: String) {
        if (workspaceId.isBlank()) {
            missingFilterCounter.increment()
            if (securityFlags.osTenantAssert) {
                throw BadRequestException("Tenant filter missing for search")
            }
        }
    }

    private fun scoreDocument(query: String, title: String, body: String): Double {
        val q = query.lowercase()
        val titleCount = title.lowercase().split(q).size - 1
        val bodyCount = body.lowercase().split(q).size - 1
        return titleCount * 2.0 + bodyCount
    }
}
