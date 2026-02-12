package com.autodoctree.api.search

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.config.SearchProperties
import com.autodoctree.api.config.SecurityFlags
import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.infra.BadRequestException
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

internal fun hasWorkspaceFilter(queryPayload: Map<String, Any?>, workspaceId: String): Boolean {
    val query = queryPayload["query"] as? Map<*, *> ?: return false
    val bool = query["bool"] as? Map<*, *> ?: return false
    val filters = bool["filter"] as? List<*> ?: return false
    return filters.any { filterItem ->
        val term = (filterItem as? Map<*, *>)?.get("term") as? Map<*, *> ?: return@any false
        term["workspace_id"]?.toString() == workspaceId
    }
}

internal fun buildNoriTemplatePayload(
    indexPattern: String,
    noriUserDictionaryRules: List<String>,
    synonymRules: List<String>
): Map<String, Any?> {
    val analysisSettings = mapOf(
        "tokenizer" to mapOf(
            "ko_nori_tokenizer" to mapOf(
                "type" to "nori_tokenizer",
                "decompound_mode" to "mixed",
                "user_dictionary_rules" to noriUserDictionaryRules
            )
        ),
        "filter" to mapOf(
            "ko_nori_pos_filter" to mapOf(
                "type" to "nori_part_of_speech",
                "stoptags" to listOf("E", "IC", "J", "MAG", "MAJ", "MM", "SP", "SSC", "SSO", "SC", "SE", "XPN", "XSA", "XSN", "XSV", "UNA", "NA", "VSV")
            ),
            "ko_synonym_filter" to mapOf(
                "type" to "synonym",
                "lenient" to true,
                "synonyms" to synonymRules
            )
        ),
        "analyzer" to mapOf(
            "ko_nori" to mapOf(
                "type" to "custom",
                "tokenizer" to "ko_nori_tokenizer",
                "filter" to listOf("lowercase", "ko_nori_pos_filter", "ko_synonym_filter")
            )
        )
    )
    return mapOf(
        "index_patterns" to listOf(indexPattern),
        "template" to mapOf(
            "settings" to mapOf(
                "number_of_shards" to 1,
                "number_of_replicas" to 0,
                "analysis" to analysisSettings
            ),
            "mappings" to mapOf(
                "properties" to mapOf(
                    "workspace_id" to mapOf("type" to "keyword"),
                    "document_id" to mapOf("type" to "keyword"),
                    "title" to mapOf(
                        "type" to "text",
                        "analyzer" to "ko_nori",
                        "search_analyzer" to "ko_nori"
                    ),
                    "body" to mapOf(
                        "type" to "text",
                        "analyzer" to "ko_nori",
                        "search_analyzer" to "ko_nori"
                    ),
                    "created_at" to mapOf("type" to "date"),
                    "updated_at" to mapOf("type" to "date")
                )
            )
        )
    )
}

internal fun buildBasicTemplatePayload(indexPattern: String): Map<String, Any?> {
    return mapOf(
        "index_patterns" to listOf(indexPattern),
        "template" to mapOf(
            "settings" to mapOf(
                "number_of_shards" to 1,
                "number_of_replicas" to 0
            ),
            "mappings" to mapOf(
                "properties" to mapOf(
                    "workspace_id" to mapOf("type" to "keyword"),
                    "document_id" to mapOf("type" to "keyword"),
                    "title" to mapOf("type" to "text"),
                    "body" to mapOf("type" to "text"),
                    "created_at" to mapOf("type" to "date"),
                    "updated_at" to mapOf("type" to "date")
                )
            )
        )
    )
}

@Component
@ConditionalOnProperty(prefix = "search", name = ["backend"], havingValue = "database")
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

@Component
@ConditionalOnProperty(prefix = "search", name = ["backend"], havingValue = "opensearch", matchIfMissing = true)
class OpenSearchTenantSearchClient(
    private val documentRepository: DocumentRepository,
    private val searchProperties: SearchProperties,
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
    private val baseUrl = searchProperties.opensearchUrl.trimEnd('/')
    private val noriUserDictionaryRules = readResourceRules("opensearch/nori_userdict.txt")
    private val synonymRules = readResourceRules("opensearch/ko_synonyms.txt")

    @PostConstruct
    fun bootstrap() {
        ensureTemplate()
        ensureAlias()
    }

    override fun search(workspaceId: String, spec: SearchSpec): List<SearchHit> {
        if (!validateWorkspaceScope(workspaceId)) {
            return emptyList()
        }

        val payload = buildSearchPayload(workspaceId, spec)
        if (!assertWorkspaceFilter(payload, workspaceId)) {
            return emptyList()
        }

        val response = execute(
            method = "POST",
            path = "/${encode(searchProperties.indexAlias)}/_search",
            body = objectMapper.writeValueAsString(payload),
            acceptedStatusCodes = setOf(200)
        )

        val root = objectMapper.readTree(response.body())
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

    override fun upsert(workspaceId: String, documentId: String) {
        if (!validateWorkspaceScope(workspaceId)) {
            return
        }

        val document = documentRepository.findByWorkspaceAndId(workspaceId, documentId) ?: run {
            delete(workspaceId, documentId)
            return
        }

        val payload = mapOf(
            "workspace_id" to workspaceId,
            "document_id" to document.id,
            "title" to document.title,
            "body" to (document.bodyText ?: document.bodyMarkdown ?: ""),
            "created_at" to document.createdAt.toString(),
            "updated_at" to document.updatedAt.toString()
        )

        execute(
            method = "PUT",
            path = "/${encode(searchProperties.indexAlias)}/_doc/${encode(docKey(workspaceId, documentId))}?refresh=true",
            body = objectMapper.writeValueAsString(payload),
            acceptedStatusCodes = setOf(200, 201)
        )
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

    private fun buildSearchPayload(workspaceId: String, spec: SearchSpec): Map<String, Any?> {
        return mapOf(
            "from" to (spec.page * spec.size),
            "size" to spec.size,
            "_source" to listOf("document_id", "title", "workspace_id"),
            "query" to mapOf(
                "bool" to mapOf(
                    "must" to listOf(
                        mapOf(
                            "simple_query_string" to mapOf(
                                "query" to spec.query,
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

    private fun ensureTemplate() {
        val templatePath = "/_index_template/${encode(searchProperties.templateName)}"
        val indexPattern = "${searchAliasPrefix()}-v1-*"
        if (!featureFlags.noriTokenizer) {
            ensureTemplateWithPayload(
                templatePath = templatePath,
                templatePayload = buildBasicTemplatePayload(indexPattern)
            )
            return
        }

        val noriTemplatePayload = buildNoriTemplatePayload(
            indexPattern = "${searchAliasPrefix()}-v1-*",
            noriUserDictionaryRules = noriUserDictionaryRules,
            synonymRules = synonymRules
        )
        val noriResponse = executeRaw(
            method = "PUT",
            path = templatePath,
            body = objectMapper.writeValueAsString(noriTemplatePayload)
        )
        if (noriResponse.statusCode() in setOf(200, 201)) {
            return
        }
        if (noriResponse.statusCode() != 400) {
            requestFailureCounter.increment()
            throw IllegalStateException("OpenSearch request failed: ${noriResponse.statusCode()}")
        }

        templateFallbackCounter.increment()
        logger.warn(
            "opensearch_template_fallback status={} reason={}",
            noriResponse.statusCode(),
            trimForLog(noriResponse.body())
        )

        ensureTemplateWithPayload(
            templatePath = templatePath,
            templatePayload = buildBasicTemplatePayload(indexPattern)
        )
    }

    private fun ensureTemplateWithPayload(
        templatePath: String,
        templatePayload: Map<String, Any?>
    ) {
        execute(
            method = "PUT",
            path = templatePath,
            body = objectMapper.writeValueAsString(templatePayload),
            acceptedStatusCodes = setOf(200, 201)
        )
    }

    private fun ensureAlias() {
        val indexName = "${searchAliasPrefix()}-v1-000001"

        val createIndexResponse = executeRaw(
            method = "PUT",
            path = "/${encode(indexName)}",
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

        val actions = mutableListOf<Map<String, Any?>>()
        if (aliasState.statusCode() == 200) {
            val current = objectMapper.readTree(aliasState.body())
            current.fieldNames().forEach { existingIndex ->
                if (existingIndex != indexName) {
                    actions += mapOf(
                        "add" to mapOf(
                            "index" to existingIndex,
                            "alias" to searchProperties.indexAlias,
                            "is_write_index" to false
                        )
                    )
                }
            }
        } else if (aliasState.statusCode() != 404) {
            requestFailureCounter.increment()
            throw IllegalStateException("Failed to read OpenSearch alias state")
        }

        actions += mapOf(
            "add" to mapOf(
                "index" to indexName,
                "alias" to searchProperties.indexAlias,
                "is_write_index" to true
            )
        )

        execute(
            method = "POST",
            path = "/_aliases",
            body = objectMapper.writeValueAsString(mapOf("actions" to actions)),
            acceptedStatusCodes = setOf(200)
        )
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

    private fun searchAliasPrefix(): String {
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
