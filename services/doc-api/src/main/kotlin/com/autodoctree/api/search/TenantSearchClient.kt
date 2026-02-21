package com.autodoctree.api.search

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.config.SearchProperties
import com.autodoctree.api.config.SecurityFlags
import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.EmbeddingRepository
import com.autodoctree.api.domain.EmbeddingAggregationService
import com.autodoctree.api.infra.BadRequestException
import com.autodoctree.api.worker.EmbeddingProvider
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

enum class SearchMode {
    BM25,
    HYBRID;

    companion object {
        fun fromApi(value: String?): SearchMode {
            return when (value?.trim()?.lowercase()) {
                "hybrid" -> HYBRID
                else -> BM25
            }
        }
    }
}

data class SearchSpec(
    val query: String,
    val page: Int,
    val size: Int,
    val mode: SearchMode = SearchMode.BM25,
    val debug: Boolean = false
)

data class SearchHit(
    val documentId: String,
    val title: String,
    val score: Double,
    val bm25Rank: Int? = null,
    val knnRank: Int? = null,
    val rrfScore: Double? = null
)

data class SearchResult(
    val hits: List<SearchHit>,
    val debug: Map<String, Any?>? = null
)

data class SearchTemplateCapabilities(
    val useNori: Boolean,
    val useIcu: Boolean,
    val useKnn: Boolean,
    val vectorDimension: Int? = null,
    val vectorField: String = "doc_embedding"
)

internal data class SearchBackendCapabilities(
    val useNori: Boolean = false,
    val useIcu: Boolean = false,
    val knnEnabled: Boolean = false,
    val vectorDimension: Int? = null
)

internal data class Bm25SearchOutcome(
    val hits: List<SearchHit>,
    val usedLegacyDsl: Boolean,
    val operator: String,
    val minimumShouldMatch: String?
)

internal data class VectorSearchOutcome(
    val hits: List<SearchHit>,
    val vectorUsed: Boolean,
    val reason: String
)

interface TenantSearchClient {
    fun search(workspaceId: String, spec: SearchSpec): SearchResult
    fun upsert(workspaceId: String, documentId: String)
    fun delete(workspaceId: String, documentId: String)
}

internal fun hasWorkspaceFilter(queryPayload: Map<String, Any?>, workspaceId: String): Boolean {
    return containsWorkspaceTerm(queryPayload, workspaceId)
}

private fun containsWorkspaceTerm(node: Any?, workspaceId: String): Boolean {
    return when (node) {
        is Map<*, *> -> {
            node.entries.any { (key, value) ->
                val keyName = key?.toString().orEmpty()
                when {
                    keyName == "term" -> workspaceTermMatches(value, workspaceId)
                    keyName == "terms" -> workspaceTermsMatch(value, workspaceId)
                    else -> containsWorkspaceTerm(value, workspaceId)
                }
            }
        }
        is List<*> -> node.any { containsWorkspaceTerm(it, workspaceId) }
        else -> false
    }
}

private fun workspaceTermMatches(raw: Any?, workspaceId: String): Boolean {
    val termMap = raw as? Map<*, *> ?: return false
    val value = termMap["workspace_id"] ?: return false
    return value.toString() == workspaceId
}

private fun workspaceTermsMatch(raw: Any?, workspaceId: String): Boolean {
    val termsMap = raw as? Map<*, *> ?: return false
    val value = termsMap["workspace_id"] ?: return false
    return when (value) {
        is List<*> -> value.any { it?.toString() == workspaceId }
        else -> value.toString() == workspaceId
    }
}

internal fun buildSearchV2TemplatePayload(
    indexPattern: String,
    capabilities: SearchTemplateCapabilities,
    noriUserDictionaryRules: List<String>,
    synonymRules: List<String>
): Map<String, Any?> {
    val tokenizerName = if (capabilities.useIcu) "icu_tokenizer" else "standard"
    val foldingFilterName = if (capabilities.useIcu) "icu_folding" else "asciifolding"

    val tokenizers = mutableMapOf<String, Any?>()

    val filters = mutableMapOf<String, Any?>(
        "edge_ngram_filter" to mapOf(
            "type" to "edge_ngram",
            "min_gram" to 1,
            "max_gram" to 20
        )
    )

    val analyzers = mutableMapOf<String, Any?>(
        "std_index" to mapOf(
            "type" to "custom",
            "tokenizer" to tokenizerName,
            "filter" to listOf("lowercase", foldingFilterName)
        ),
        "std_search" to mapOf(
            "type" to "custom",
            "tokenizer" to tokenizerName,
            "filter" to listOf("lowercase", foldingFilterName)
        ),
        "autocomplete_index" to mapOf(
            "type" to "custom",
            "tokenizer" to tokenizerName,
            "filter" to listOf("lowercase", foldingFilterName, "edge_ngram_filter")
        ),
        "autocomplete_search" to mapOf(
            "type" to "custom",
            "tokenizer" to tokenizerName,
            "filter" to listOf("lowercase", foldingFilterName)
        )
    )

    val koAnalyzerName = if (capabilities.useNori) "ko_nori" else "std_index"
    if (capabilities.useNori) {
        tokenizers["ko_nori_tokenizer"] = mapOf(
            "type" to "nori_tokenizer",
            "decompound_mode" to "mixed",
            "user_dictionary_rules" to noriUserDictionaryRules
        )
        filters["ko_nori_pos_filter"] = mapOf(
            "type" to "nori_part_of_speech",
            "stoptags" to listOf("E", "IC", "J", "MAG", "MAJ", "MM", "SP", "SSC", "SSO", "SC", "SE", "XPN", "XSA", "XSN", "XSV", "UNA", "NA", "VSV")
        )
        filters["ko_nori_readingform"] = mapOf("type" to "nori_readingform")
        if (synonymRules.isNotEmpty()) {
            filters["ko_synonym_filter"] = mapOf(
                "type" to "synonym",
                "lenient" to true,
                "synonyms" to synonymRules
            )
        }

        val koFilters = mutableListOf("lowercase", "ko_nori_readingform", "ko_nori_pos_filter")
        if (synonymRules.isNotEmpty()) {
            koFilters += "ko_synonym_filter"
        }
        analyzers["ko_nori"] = mapOf(
            "type" to "custom",
            "tokenizer" to "ko_nori_tokenizer",
            "filter" to koFilters
        )
    }

    val titleFields = mutableMapOf<String, Any?>(
        "ko" to mapOf(
            "type" to "text",
            "analyzer" to koAnalyzerName,
            "search_analyzer" to if (capabilities.useNori) "ko_nori" else "std_search"
        ),
        "std" to mapOf(
            "type" to "text",
            "analyzer" to "std_index",
            "search_analyzer" to "std_search"
        ),
        "edge" to mapOf(
            "type" to "text",
            "analyzer" to "autocomplete_index",
            "search_analyzer" to "autocomplete_search"
        ),
        "keyword" to mapOf(
            "type" to "keyword",
            "ignore_above" to 512
        )
    )

    val bodyFields = mutableMapOf<String, Any?>(
        "ko" to mapOf(
            "type" to "text",
            "analyzer" to koAnalyzerName,
            "search_analyzer" to if (capabilities.useNori) "ko_nori" else "std_search"
        ),
        "std" to mapOf(
            "type" to "text",
            "analyzer" to "std_index",
            "search_analyzer" to "std_search"
        )
    )

    val properties = mutableMapOf<String, Any?>(
        "workspace_id" to mapOf("type" to "keyword"),
        "document_id" to mapOf("type" to "keyword"),
        "title" to mapOf(
            "type" to "text",
            "analyzer" to "std_index",
            "search_analyzer" to "std_search",
            "fields" to titleFields
        ),
        "body" to mapOf(
            "type" to "text",
            "analyzer" to "std_index",
            "search_analyzer" to "std_search",
            "fields" to bodyFields
        ),
        "created_at" to mapOf("type" to "date"),
        "updated_at" to mapOf("type" to "date")
    )

    if (capabilities.useKnn && (capabilities.vectorDimension ?: 0) > 0) {
        properties[capabilities.vectorField] = mapOf(
            "type" to "knn_vector",
            "dimension" to capabilities.vectorDimension,
            "method" to mapOf(
                "name" to "hnsw",
                "space_type" to "cosinesimil",
                "engine" to "nmslib",
                "parameters" to mapOf(
                    "ef_construction" to 128,
                    "m" to 24
                )
            )
        )
    }

    val settings = mutableMapOf<String, Any?>(
        "number_of_shards" to 1,
        "number_of_replicas" to 0,
        "index.max_ngram_diff" to 19,
        "analysis" to mapOf(
            "tokenizer" to tokenizers,
            "filter" to filters,
            "analyzer" to analyzers
        )
    )

    if (capabilities.useKnn && (capabilities.vectorDimension ?: 0) > 0) {
        settings["knn"] = true
        settings["index.knn"] = true
    }

    return mapOf(
        "index_patterns" to listOf(indexPattern),
        "template" to mapOf(
            "settings" to settings,
            "mappings" to mapOf(
                "properties" to properties
            )
        )
    )
}

internal fun buildNoriTemplatePayload(
    indexPattern: String,
    noriUserDictionaryRules: List<String>,
    synonymRules: List<String>
): Map<String, Any?> {
    return buildSearchV2TemplatePayload(
        indexPattern = indexPattern,
        capabilities = SearchTemplateCapabilities(
            useNori = true,
            useIcu = false,
            useKnn = false
        ),
        noriUserDictionaryRules = noriUserDictionaryRules,
        synonymRules = synonymRules
    )
}

internal fun buildBasicTemplatePayload(indexPattern: String): Map<String, Any?> {
    return buildSearchV2TemplatePayload(
        indexPattern = indexPattern,
        capabilities = SearchTemplateCapabilities(
            useNori = false,
            useIcu = false,
            useKnn = false
        ),
        noriUserDictionaryRules = emptyList(),
        synonymRules = emptyList()
    )
}

internal fun buildBm25Payload(
    workspaceId: String,
    query: String,
    from: Int,
    size: Int,
    operator: String,
    minimumShouldMatch: String?
): Map<String, Any?> {
    val multiMatch = mutableMapOf<String, Any?>(
        "query" to query,
        "type" to "best_fields",
        "fields" to listOf(
            "title.ko^3",
            "title.std^2",
            "body.ko^1.2",
            "body.std^1.0",
            "title.edge^0.2"
        ),
        "operator" to operator
    )
    if (!minimumShouldMatch.isNullOrBlank()) {
        multiMatch["minimum_should_match"] = minimumShouldMatch
    }

    return mapOf(
        "from" to from,
        "size" to size,
        "_source" to listOf("document_id", "title", "workspace_id"),
        "query" to mapOf(
            "bool" to mapOf(
                "must" to listOf(
                    mapOf("multi_match" to multiMatch)
                ),
                "filter" to listOf(
                    mapOf("term" to mapOf("workspace_id" to workspaceId))
                )
            )
        )
    )
}

internal fun buildLegacySearchPayload(workspaceId: String, query: String, from: Int, size: Int): Map<String, Any?> {
    return mapOf(
        "from" to from,
        "size" to size,
        "_source" to listOf("document_id", "title", "workspace_id"),
        "query" to mapOf(
            "bool" to mapOf(
                "must" to listOf(
                    mapOf(
                        "simple_query_string" to mapOf(
                            "query" to query,
                            "fields" to listOf("title^2", "body"),
                            "default_operator" to "and"
                        )
                    )
                ),
                "filter" to listOf(
                    mapOf("term" to mapOf("workspace_id" to workspaceId))
                )
            )
        )
    )
}

internal fun buildKnnPayload(
    workspaceId: String,
    vectorField: String,
    queryVector: List<Double>,
    size: Int
): Map<String, Any?> {
    return mapOf(
        "size" to size,
        "_source" to listOf("document_id", "title", "workspace_id"),
        "query" to mapOf(
            "knn" to mapOf(
                vectorField to mapOf(
                    "vector" to queryVector,
                    "k" to size,
                    "filter" to mapOf(
                        "term" to mapOf("workspace_id" to workspaceId)
                    )
                )
            )
        )
    )
}

internal fun detectQueryLanguage(query: String): String {
    if (query.any { it in '\uAC00'..'\uD7A3' }) {
        return "ko"
    }
    if (query.any { it in '\u4E00'..'\u9FFF' || it in '\u3040'..'\u30FF' }) {
        return "cjk"
    }
    return "generic"
}

internal fun mergeWithRrf(
    bm25Hits: List<SearchHit>,
    knnHits: List<SearchHit>,
    rrfK: Int,
    page: Int,
    size: Int
): List<SearchHit> {
    data class Accumulator(
        var title: String,
        var bm25Rank: Int? = null,
        var knnRank: Int? = null,
        var rrf: Double = 0.0
    )

    val acc = linkedMapOf<String, Accumulator>()
    bm25Hits.forEachIndexed { index, hit ->
        val rank = index + 1
        val current = acc.getOrPut(hit.documentId) { Accumulator(title = hit.title) }
        if (current.title.isBlank()) {
            current.title = hit.title
        }
        current.bm25Rank = rank
        current.rrf += 1.0 / (rrfK + rank).toDouble()
    }

    knnHits.forEachIndexed { index, hit ->
        val rank = index + 1
        val current = acc.getOrPut(hit.documentId) { Accumulator(title = hit.title) }
        if (current.title.isBlank()) {
            current.title = hit.title
        }
        current.knnRank = rank
        current.rrf += 1.0 / (rrfK + rank).toDouble()
    }

    val sorted = acc.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Accumulator>> { it.value.rrf }
                .thenBy { it.value.bm25Rank ?: Int.MAX_VALUE }
                .thenBy { it.value.knnRank ?: Int.MAX_VALUE }
                .thenBy { it.key }
        )

    val offset = page * size
    return sorted
        .drop(offset)
        .take(size)
        .map { (documentId, value) ->
            SearchHit(
                documentId = documentId,
                title = value.title,
                score = value.rrf,
                bm25Rank = value.bm25Rank,
                knnRank = value.knnRank,
                rrfScore = value.rrf
            )
        }
}

@Component
@ConditionalOnProperty(prefix = "search", name = ["backend"], havingValue = "database")
class DatabaseTenantSearchClient(
    private val documentRepository: DocumentRepository,
    private val securityFlags: SecurityFlags,
    meterRegistry: MeterRegistry
) : TenantSearchClient {

    private val missingFilterCounter = meterRegistry.counter("security.os_missing_tenant_filter_total")

    override fun search(workspaceId: String, spec: SearchSpec): SearchResult {
        ensureWorkspaceScope(workspaceId)
        val docs = documentRepository.searchByWorkspace(
            workspaceId = workspaceId,
            query = spec.query,
            size = spec.size,
            offset = spec.page * spec.size
        )
        val hits = docs.map {
            val score = scoreDocument(spec.query, it.title, it.bodyText ?: "")
            SearchHit(
                documentId = it.id,
                title = it.title,
                score = score
            )
        }.sortedByDescending { it.score }

        val debug = if (spec.debug) {
            mapOf(
                "workspace_id" to workspaceId,
                "index_alias" to "database",
                "resolved_index_name" to emptyList<String>(),
                "workspace_indexed_doc_count" to docs.size,
                "search_backend" to "database",
                "lang_detected" to detectQueryLanguage(spec.query),
                "vector_used" to false
            )
        } else {
            null
        }

        return SearchResult(hits = hits, debug = debug)
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

@Component
@ConditionalOnProperty(prefix = "search", name = ["backend"], havingValue = "opensearch", matchIfMissing = true)
class OpenSearchTenantSearchClient(
    private val documentRepository: DocumentRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val embeddingAggregationService: EmbeddingAggregationService,
    private val embeddingProvider: EmbeddingProvider,
    private val searchProperties: SearchProperties,
    private val treeProperties: TreeProperties,
    private val featureFlags: FeatureFlags,
    private val securityFlags: SecurityFlags,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry
) : TenantSearchClient {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val logger = LoggerFactory.getLogger(javaClass)
    private val missingFilterCounter = meterRegistry.counter("security.os_missing_tenant_filter_total")
    private val requestFailureCounter = meterRegistry.counter("search.opensearch.request_failure_total")
    private val templateFallbackCounter = meterRegistry.counter("search.opensearch.template_fallback_total")
    private val upsertSuccessCounter = meterRegistry.counter("search.opensearch.upsert_success_total")
    private val upsertFailureCounter = meterRegistry.counter("search.opensearch.upsert_failure_total")
    private val upsertEmbeddingMissingCounter = meterRegistry.counter("search.opensearch.upsert_embedding_missing_total")
    private val upsertEmbeddingAttachedCounter = meterRegistry.counter("search.opensearch.upsert_embedding_attached_total")
    private val baseUrl = searchProperties.opensearchUrl.trimEnd('/')
    private val noriUserDictionaryRules = readResourceRules("opensearch/nori_userdict.txt")
    private val synonymRules = readResourceRules("opensearch/ko_synonyms.txt")
    private val backendCapabilities = AtomicReference(SearchBackendCapabilities())

    @Volatile
    private var vectorQueryEnabled: Boolean = false

    @PostConstruct
    fun bootstrap() {
        val pluginComponents = loadPluginComponents()
        val requestedCapabilities = requestedTemplateCapabilities(pluginComponents)
        val appliedCapabilities = ensureTemplate(requestedCapabilities)
        backendCapabilities.set(
            SearchBackendCapabilities(
                useNori = appliedCapabilities.useNori,
                useIcu = appliedCapabilities.useIcu,
                knnEnabled = appliedCapabilities.useKnn,
                vectorDimension = appliedCapabilities.vectorDimension
            )
        )
        vectorQueryEnabled = appliedCapabilities.useKnn
        ensureAlias()
    }

    override fun search(workspaceId: String, spec: SearchSpec): SearchResult {
        if (!validateWorkspaceScope(workspaceId)) {
            return SearchResult(emptyList())
        }

        val requestedMode = spec.mode
        val hybridEnabled = requestedMode == SearchMode.HYBRID && featureFlags.hybridSearch
        val language = detectQueryLanguage(spec.query)
        val candidateSize = if (hybridEnabled) {
            max((spec.page + 1) * spec.size, searchProperties.hybridCandidateSize).coerceAtLeast(spec.size)
        } else {
            spec.size
        }

        var bm25Outcome = runBm25Query(
            workspaceId = workspaceId,
            query = spec.query,
            from = if (hybridEnabled) 0 else spec.page * spec.size,
            size = candidateSize,
            operator = "and",
            minimumShouldMatch = null
        )

        var fallbackApplied = false
        if (bm25Outcome.hits.isEmpty() && tokenCount(spec.query) > 1) {
            fallbackApplied = true
            bm25Outcome = runBm25Query(
                workspaceId = workspaceId,
                query = spec.query,
                from = if (hybridEnabled) 0 else spec.page * spec.size,
                size = candidateSize,
                operator = "or",
                minimumShouldMatch = searchProperties.hybridOperatorFallbackMinimumShouldMatch
            )
        }

        val vectorOutcome = when {
            hybridEnabled -> runVectorQuery(workspaceId = workspaceId, query = spec.query, size = candidateSize)
            requestedMode == SearchMode.HYBRID && !featureFlags.hybridSearch -> VectorSearchOutcome(
                hits = emptyList(),
                vectorUsed = false,
                reason = "hybrid_feature_disabled"
            )
            else -> VectorSearchOutcome(hits = emptyList(), vectorUsed = false, reason = "bm25_mode")
        }

        val finalHits = when {
            hybridEnabled && vectorOutcome.vectorUsed -> mergeWithRrf(
                bm25Hits = bm25Outcome.hits,
                knnHits = vectorOutcome.hits,
                rrfK = searchProperties.hybridRrfK,
                page = spec.page,
                size = spec.size
            )
            hybridEnabled -> bm25Outcome.hits
                .drop(spec.page * spec.size)
                .take(spec.size)
                .mapIndexed { index, hit ->
                    hit.copy(bm25Rank = index + 1)
                }
            else -> bm25Outcome.hits
        }

        val resolvedIndices = if (spec.debug) resolveAliasIndices() else emptyList()
        val workspaceIndexedDocCount = if (spec.debug) countWorkspaceDocuments(workspaceId) else null
        val effectiveBackend = when {
            hybridEnabled && vectorOutcome.vectorUsed -> "hybrid"
            hybridEnabled -> "bm25_fallback"
            else -> "bm25"
        }

        logger.info(
            "search_query_summary workspace_id={} mode={} effective_backend={} hits={} vector_used={} lang={} alias={}",
            workspaceId,
            requestedMode.name.lowercase(Locale.getDefault()),
            effectiveBackend,
            finalHits.size,
            vectorOutcome.vectorUsed,
            language,
            searchProperties.indexAlias
        )

        val debugPayload = if (spec.debug) {
            mapOf(
                "workspace_id" to workspaceId,
                "index_alias" to searchProperties.indexAlias,
                "resolved_index_name" to resolvedIndices,
                "workspace_indexed_doc_count" to workspaceIndexedDocCount,
                "search_backend" to effectiveBackend,
                "lang_detected" to language,
                "vector_used" to vectorOutcome.vectorUsed,
                "vector_reason" to vectorOutcome.reason,
                "bm25_operator" to bm25Outcome.operator,
                "bm25_minimum_should_match" to bm25Outcome.minimumShouldMatch,
                "bm25_legacy_fallback" to bm25Outcome.usedLegacyDsl,
                "bm25_recall_fallback_applied" to fallbackApplied,
                "top_ranks" to finalHits.take(20).map {
                    mapOf(
                        "document_id" to it.documentId,
                        "bm25_rank" to it.bm25Rank,
                        "knn_rank" to it.knnRank,
                        "rrf_score" to it.rrfScore,
                        "score" to it.score
                    )
                }
            )
        } else {
            null
        }

        return SearchResult(hits = finalHits, debug = debugPayload)
    }

    override fun upsert(workspaceId: String, documentId: String) {
        if (!validateWorkspaceScope(workspaceId)) {
            return
        }

        val document = documentRepository.findByWorkspaceAndId(workspaceId, documentId) ?: run {
            delete(workspaceId, documentId)
            return
        }

        val embedding = loadDocumentEmbeddingVector(workspaceId, documentId)
        val payload = mutableMapOf<String, Any?>(
            "workspace_id" to workspaceId,
            "document_id" to document.id,
            "title" to document.title,
            "body" to (document.bodyText ?: document.bodyMarkdown ?: ""),
            "created_at" to document.createdAt.toString(),
            "updated_at" to document.updatedAt.toString()
        )

        if (!embedding.isNullOrEmpty()) {
            payload[searchProperties.vectorField] = embedding
            upsertEmbeddingAttachedCounter.increment()
        } else {
            upsertEmbeddingMissingCounter.increment()
        }

        try {
            execute(
                method = "PUT",
                path = "/${encode(searchProperties.indexAlias)}/_doc/${encode(docKey(workspaceId, documentId))}?refresh=true",
                body = objectMapper.writeValueAsString(payload),
                acceptedStatusCodes = setOf(200, 201)
            )
            upsertSuccessCounter.increment()
            logger.info(
                "search_upsert workspace_id={} document_id={} embedding_attached={} alias={}",
                workspaceId,
                documentId,
                !embedding.isNullOrEmpty(),
                searchProperties.indexAlias
            )
            sampleIndexSyncHealth(workspaceId)
        } catch (ex: Exception) {
            upsertFailureCounter.increment()
            logger.warn(
                "search_upsert_failed workspace_id={} document_id={} alias={} reason={}",
                workspaceId,
                documentId,
                searchProperties.indexAlias,
                ex.message
            )
            throw ex
        }
    }

    override fun delete(workspaceId: String, documentId: String) {
        if (!validateWorkspaceScope(workspaceId)) {
            return
        }

        val payload = mapOf(
            "query" to mapOf(
                "bool" to mapOf(
                    "filter" to listOf(
                        mapOf("term" to mapOf("workspace_id" to workspaceId)),
                        mapOf("term" to mapOf("document_id" to documentId))
                    )
                )
            )
        )

        if (!assertWorkspaceFilter(payload, workspaceId)) {
            return
        }

        execute(
            method = "POST",
            path = "/${encode(searchProperties.indexAlias)}/_delete_by_query?refresh=true&conflicts=proceed",
            body = objectMapper.writeValueAsString(payload),
            acceptedStatusCodes = setOf(200)
        )
    }

    private fun runBm25Query(
        workspaceId: String,
        query: String,
        from: Int,
        size: Int,
        operator: String,
        minimumShouldMatch: String?
    ): Bm25SearchOutcome {
        val payload = buildBm25Payload(
            workspaceId = workspaceId,
            query = query,
            from = from,
            size = size,
            operator = operator,
            minimumShouldMatch = minimumShouldMatch
        )
        if (!assertWorkspaceFilter(payload, workspaceId)) {
            return Bm25SearchOutcome(emptyList(), false, operator, minimumShouldMatch)
        }

        val response = executeRaw(
            method = "POST",
            path = "/${encode(searchProperties.indexAlias)}/_search",
            body = objectMapper.writeValueAsString(payload)
        )

        if (response.statusCode() == 200) {
            return Bm25SearchOutcome(
                hits = parseSearchHits(response.body()).mapIndexed { index, hit -> hit.copy(bm25Rank = index + 1) },
                usedLegacyDsl = false,
                operator = operator,
                minimumShouldMatch = minimumShouldMatch
            )
        }

        logger.warn(
            "bm25_query_failed status={} operator={} msm={} body={}",
            response.statusCode(),
            operator,
            minimumShouldMatch,
            trimForLog(response.body())
        )

        val legacyPayload = buildLegacySearchPayload(
            workspaceId = workspaceId,
            query = query,
            from = from,
            size = size
        )
        if (!assertWorkspaceFilter(legacyPayload, workspaceId)) {
            return Bm25SearchOutcome(emptyList(), true, operator, minimumShouldMatch)
        }

        val legacyResponse = executeRaw(
            method = "POST",
            path = "/${encode(searchProperties.indexAlias)}/_search",
            body = objectMapper.writeValueAsString(legacyPayload)
        )

        if (legacyResponse.statusCode() == 200) {
            return Bm25SearchOutcome(
                hits = parseSearchHits(legacyResponse.body()).mapIndexed { index, hit -> hit.copy(bm25Rank = index + 1) },
                usedLegacyDsl = true,
                operator = operator,
                minimumShouldMatch = minimumShouldMatch
            )
        }

        requestFailureCounter.increment()
        logger.warn(
            "bm25_legacy_query_failed status={} body={}",
            legacyResponse.statusCode(),
            trimForLog(legacyResponse.body())
        )
        return Bm25SearchOutcome(emptyList(), true, operator, minimumShouldMatch)
    }

    private fun runVectorQuery(workspaceId: String, query: String, size: Int): VectorSearchOutcome {
        val capabilities = backendCapabilities.get()
        if (!capabilities.knnEnabled || !vectorQueryEnabled) {
            return VectorSearchOutcome(emptyList(), false, "vector_capability_disabled")
        }

        val queryEmbedding = runCatching {
            embeddingProvider.embed(listOf(query)).firstOrNull()
        }.getOrNull()

        if (queryEmbedding.isNullOrEmpty()) {
            return VectorSearchOutcome(emptyList(), false, "query_embedding_unavailable")
        }

        val payload = buildKnnPayload(
            workspaceId = workspaceId,
            vectorField = searchProperties.vectorField,
            queryVector = queryEmbedding,
            size = max(size, searchProperties.hybridKnnTopK)
        )

        if (!assertWorkspaceFilter(payload, workspaceId)) {
            return VectorSearchOutcome(emptyList(), false, "tenant_filter_missing")
        }

        val response = executeRaw(
            method = "POST",
            path = "/${encode(searchProperties.indexAlias)}/_search",
            body = objectMapper.writeValueAsString(payload)
        )

        if (response.statusCode() == 200) {
            return VectorSearchOutcome(
                hits = parseSearchHits(response.body()).mapIndexed { index, hit -> hit.copy(knnRank = index + 1) },
                vectorUsed = true,
                reason = "ok"
            )
        }

        requestFailureCounter.increment()
        logger.warn(
            "vector_query_failed status={} body={}",
            response.statusCode(),
            trimForLog(response.body())
        )

        if (response.statusCode() in setOf(400, 404)) {
            vectorQueryEnabled = false
        }

        return VectorSearchOutcome(emptyList(), false, "vector_query_failed")
    }

    private fun parseSearchHits(responseBody: String): List<SearchHit> {
        val root = objectMapper.readTree(responseBody)
        val hitsNode = root.path("hits").path("hits")
        if (!hitsNode.isArray) {
            return emptyList()
        }

        return hitsNode.mapNotNull { hit ->
            val source = hit.path("_source")
            val documentId = source.path("document_id").asText(null) ?: return@mapNotNull null
            val title = source.path("title").asText("")
            val score = hit.path("_score").asDouble(0.0)
            SearchHit(documentId = documentId, title = title, score = score)
        }
    }

    private fun loadDocumentEmbeddingVector(workspaceId: String, documentId: String): List<Double>? {
        if (!backendCapabilities.get().knnEnabled) {
            return null
        }

        val modelVersion = embeddingProvider.modelVersion()

        val existing = embeddingRepository.findDocEmbedding(workspaceId, documentId, modelVersion)
        val existingVector = parseVector(existing?.vectorJson)
        if (!existingVector.isNullOrEmpty()) {
            return existingVector
        }

        val rows = embeddingRepository.listByWorkspaceAndDocumentAndModel(workspaceId, documentId, modelVersion)
        if (rows.isEmpty()) {
            return null
        }

        val aggregated = embeddingAggregationService.aggregateForTree(
            embeddings = rows,
            treeProperties = treeProperties,
            qualityByDocument = emptyMap()
        )[documentId] ?: return null

        val vector = parseVector(aggregated.vectorJson)
        if (vector.isNullOrEmpty()) {
            return null
        }

        embeddingRepository.upsert(
            workspaceId = workspaceId,
            documentId = documentId,
            targetType = "DOCUMENT",
            targetId = documentId,
            inputHash = aggregated.inputHash,
            vectorJson = aggregated.vectorJson,
            modelVersion = modelVersion
        )

        return vector
    }

    private fun parseVector(vectorJson: String?): List<Double>? {
        if (vectorJson.isNullOrBlank()) {
            return null
        }
        return runCatching {
            objectMapper.readValue(vectorJson, List::class.java)
                .mapNotNull { (it as? Number)?.toDouble() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun sampleIndexSyncHealth(workspaceId: String) {
        val sampleRate = searchProperties.indexSyncSampleRate.coerceIn(0.0, 1.0)
        if (sampleRate <= 0.0) {
            return
        }
        if (ThreadLocalRandom.current().nextDouble() > sampleRate) {
            return
        }

        val dbCount = documentRepository.countByWorkspace(workspaceId, null, null)
        val indexedCount = countWorkspaceDocuments(workspaceId)
        logger.info(
            "search_index_sync_sample workspace_id={} db_count={} indexed_count={} gap={}",
            workspaceId,
            dbCount,
            indexedCount,
            if (indexedCount == null) "unknown" else (dbCount - indexedCount)
        )
    }

    private fun countWorkspaceDocuments(workspaceId: String): Long? {
        val payload = mapOf(
            "query" to mapOf(
                "term" to mapOf("workspace_id" to workspaceId)
            )
        )
        if (!assertWorkspaceFilter(payload, workspaceId)) {
            return null
        }

        val response = executeRaw(
            method = "POST",
            path = "/${encode(searchProperties.indexAlias)}/_count",
            body = objectMapper.writeValueAsString(payload)
        )

        if (response.statusCode() != 200) {
            requestFailureCounter.increment()
            logger.warn(
                "workspace_index_count_failed status={} alias={} workspace_id={} body={}",
                response.statusCode(),
                searchProperties.indexAlias,
                workspaceId,
                trimForLog(response.body())
            )
            return null
        }

        return objectMapper.readTree(response.body()).path("count").asLong(0)
    }

    private fun resolveAliasIndices(): List<String> {
        val response = executeRaw(
            method = "GET",
            path = "/_alias/${encode(searchProperties.indexAlias)}",
            body = null
        )
        if (response.statusCode() == 404) {
            return emptyList()
        }
        if (response.statusCode() != 200) {
            requestFailureCounter.increment()
            logger.warn(
                "resolve_alias_failed status={} alias={} body={}",
                response.statusCode(),
                searchProperties.indexAlias,
                trimForLog(response.body())
            )
            return emptyList()
        }

        val root = objectMapper.readTree(response.body())
        if (!root.isObject) {
            return emptyList()
        }

        val names = mutableListOf<String>()
        root.fieldNames().forEachRemaining { names += it }
        return names.sorted()
    }

    private fun tokenCount(query: String): Int {
        return query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
    }

    private fun requestedTemplateCapabilities(pluginComponents: Set<String>): SearchTemplateCapabilities {
        val normalized = pluginComponents.map { it.lowercase(Locale.getDefault()) }
        val supportsIcu = normalized.any { it.contains("analysis-icu") || it.contains("icu") }
        val supportsKnn = normalized.any { it.contains("knn") }
        val supportsNori = normalized.isEmpty() || normalized.any { it.contains("analysis-nori") || it.contains("nori") }

        val vectorDimension = if (supportsKnn) detectEmbeddingDimension() else null

        if (supportsKnn && (vectorDimension ?: 0) <= 0) {
            logger.warn("vector_dimension_detection_failed mode=lexical_only")
        }

        return SearchTemplateCapabilities(
            useNori = featureFlags.noriTokenizer && supportsNori,
            useIcu = supportsIcu,
            useKnn = supportsKnn && (vectorDimension ?: 0) > 0,
            vectorDimension = vectorDimension,
            vectorField = searchProperties.vectorField
        )
    }

    private fun ensureTemplate(capabilities: SearchTemplateCapabilities): SearchTemplateCapabilities {
        val templatePath = "/_index_template/${encode(searchProperties.templateName)}"
        val indexPattern = "${searchIndexPrefix()}-${searchProperties.indexVersion}-*"

        val attempts = buildList {
            add(capabilities)
            if (capabilities.useKnn) {
                add(capabilities.copy(useKnn = false, vectorDimension = null))
            }
            if (capabilities.useNori || capabilities.useIcu) {
                add(
                    capabilities.copy(
                        useNori = false,
                        useIcu = false,
                        useKnn = false,
                        vectorDimension = null
                    )
                )
            }
        }.distinct()

        attempts.forEachIndexed { index, current ->
            val payload = buildSearchV2TemplatePayload(
                indexPattern = indexPattern,
                capabilities = current,
                noriUserDictionaryRules = noriUserDictionaryRules,
                synonymRules = synonymRules
            )

            val response = executeRaw(
                method = "PUT",
                path = templatePath,
                body = objectMapper.writeValueAsString(payload)
            )

            if (response.statusCode() in setOf(200, 201)) {
                if (index > 0) {
                    templateFallbackCounter.increment()
                }
                return current
            }

            if (response.statusCode() !in setOf(400, 404)) {
                requestFailureCounter.increment()
                throw IllegalStateException("OpenSearch request failed: ${response.statusCode()}")
            }

            logger.warn(
                "opensearch_template_attempt_failed status={} attempt={} nori={} icu={} knn={} body={}",
                response.statusCode(),
                index,
                current.useNori,
                current.useIcu,
                current.useKnn,
                trimForLog(response.body())
            )
        }

        throw IllegalStateException("Failed to install OpenSearch template for alias ${searchProperties.indexAlias}")
    }

    private fun ensureAlias() {
        val bootstrapIndexName = "${searchIndexPrefix()}-${searchProperties.indexVersion}-000001"

        val createIndexResponse = executeRaw(
            method = "PUT",
            path = "/${encode(bootstrapIndexName)}",
            body = "{}"
        )

        if (createIndexResponse.statusCode() !in setOf(200, 201) &&
            !createIndexResponse.body().contains("resource_already_exists_exception")
        ) {
            requestFailureCounter.increment()
            throw IllegalStateException("Failed to bootstrap OpenSearch index")
        }

        val aliasState = executeRaw(
            method = "GET",
            path = "/_alias/${encode(searchProperties.indexAlias)}",
            body = null
        )

        if (aliasState.statusCode() == 200) {
            return
        }

        if (aliasState.statusCode() != 404) {
            requestFailureCounter.increment()
            throw IllegalStateException("Failed to read OpenSearch alias state")
        }

        val payload = mapOf(
            "actions" to listOf(
                mapOf(
                    "add" to mapOf(
                        "index" to bootstrapIndexName,
                        "alias" to searchProperties.indexAlias,
                        "is_write_index" to true
                    )
                )
            )
        )

        execute(
            method = "POST",
            path = "/_aliases",
            body = objectMapper.writeValueAsString(payload),
            acceptedStatusCodes = setOf(200)
        )
    }

    private fun loadPluginComponents(): Set<String> {
        val response = executeRaw(
            method = "GET",
            path = "/_cat/plugins?format=json&h=component",
            body = null
        )
        if (response.statusCode() != 200) {
            logger.warn(
                "opensearch_plugin_probe_failed status={} body={}",
                response.statusCode(),
                trimForLog(response.body())
            )
            return emptySet()
        }

        val root = objectMapper.readTree(response.body())
        if (!root.isArray) {
            return emptySet()
        }
        val components = mutableSetOf<String>()
        root.forEach { row ->
            val component = row.path("component").asText().trim()
            if (component.isNotBlank()) {
                components += component
            }
        }
        return components
    }

    private fun detectEmbeddingDimension(): Int? {
        val vector = runCatching {
            embeddingProvider.embed(listOf("embedding_dimension_probe")).firstOrNull()
        }.getOrNull()
        val dimension = vector?.size ?: 0
        return if (dimension > 0) dimension else null
    }

    private fun validateWorkspaceScope(workspaceId: String): Boolean {
        if (workspaceId.isNotBlank()) {
            return true
        }
        missingFilterCounter.increment()
        if (securityFlags.osTenantAssert) {
            throw BadRequestException("Tenant filter missing for search")
        }
        return false
    }

    private fun assertWorkspaceFilter(payload: Map<String, Any?>, workspaceId: String): Boolean {
        if (hasWorkspaceFilter(payload, workspaceId)) {
            return true
        }
        missingFilterCounter.increment()
        if (securityFlags.osTenantAssert) {
            throw BadRequestException("Tenant filter missing for search")
        }
        return false
    }

    private fun searchIndexPrefix(): String {
        val configured = searchProperties.indexPrefix.trim()
        if (configured.isNotBlank()) {
            return configured
        }
        return searchProperties.indexAlias.removeSuffix("-active")
    }

    private fun docKey(workspaceId: String, documentId: String): String {
        return "${workspaceId}_$documentId"
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    private fun execute(
        method: String,
        path: String,
        body: String?,
        acceptedStatusCodes: Set<Int>
    ): HttpResponse<String> {
        val response = executeRaw(method, path, body)
        if (response.statusCode() !in acceptedStatusCodes) {
            requestFailureCounter.increment()
            throw IllegalStateException("OpenSearch request failed: ${response.statusCode()} body=${trimForLog(response.body())}")
        }
        return response
    }

    private fun executeRaw(method: String, path: String, body: String?): HttpResponse<String> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")

        val username = searchProperties.username?.trim().orEmpty()
        val password = searchProperties.password?.trim().orEmpty()
        if (username.isNotEmpty() && password.isNotEmpty()) {
            val token = Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
            requestBuilder.header("Authorization", "Basic $token")
        }

        if (body == null) {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            requestBuilder
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
        }

        return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun trimForLog(payload: String?): String {
        if (payload.isNullOrBlank()) {
            return "<empty>"
        }
        return payload
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(securityFlags.logMaxStringLength)
    }

    private fun readResourceRules(path: String): List<String> {
        val resource = ClassPathResource(path)
        if (!resource.exists()) {
            return emptyList()
        }
        return resource.inputStream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotBlank() }
                .filterNot { it.startsWith("#") }
                .toList()
        }
    }
}
