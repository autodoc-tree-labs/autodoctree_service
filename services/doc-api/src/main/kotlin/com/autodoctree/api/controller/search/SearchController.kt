package com.autodoctree.api.controller.search

import com.autodoctree.api.domain.search.SearchService
import com.autodoctree.api.tenant.WorkspaceContextResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/search")
class SearchController(
    private val workspaceContextResolver: WorkspaceContextResolver,
    private val searchService: SearchService
) {

    @GetMapping
    fun search(
        request: HttpServletRequest,
        @RequestParam q: String,
        @RequestParam(defaultValue = "bm25") mode: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "relevance") sort: String,
        @RequestParam(defaultValue = "false") titleOnly: Boolean,
        @RequestParam(required = false) createdBy: String?,
        @RequestParam(required = false) updatedBy: String?,
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
        @RequestParam(defaultValue = "workspace") scope: String,
        @RequestParam(required = false) scopePageId: String?,
        @RequestParam(defaultValue = "false") debug: Boolean
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return searchService.search(
            context = context,
            q = q,
            page = page,
            size = size,
            mode = mode,
            sort = sort,
            titleOnly = titleOnly,
            createdBy = createdBy,
            updatedBy = updatedBy,
            fromDate = fromDate,
            toDate = toDate,
            scope = scope,
            scopePageId = scopePageId,
            debug = debug
        )
    }

    @GetMapping("/history")
    fun searchHistory(request: HttpServletRequest, @RequestParam(defaultValue = "30") limit: Int): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return searchService.listHistory(context, limit)
    }

    @PostMapping("/history")
    fun saveSearchHistory(request: HttpServletRequest, @Valid @RequestBody body: SearchHistoryRequest): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        searchService.recordHistory(context, body.eventType, body.queryText, body.documentId, body.commandKey)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }
}

data class SearchHistoryRequest(
    @field:NotBlank val eventType: String,
    val queryText: String? = null,
    val documentId: String? = null,
    val commandKey: String? = null
)
