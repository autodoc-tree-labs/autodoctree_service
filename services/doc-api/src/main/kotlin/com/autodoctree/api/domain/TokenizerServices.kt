package com.autodoctree.api.domain

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.config.SearchProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.Locale

interface TreeTokenizer {
    fun tokenize(text: String): List<String>
}

@Component
class FallbackTokenizer : TreeTokenizer {
    private val stopwords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "your", "have", "will", "about", "document",
        "draft", "note", "misc", "general", "null", "none",
        "문서", "초안", "메모", "내용", "테스트", "그리고", "하지만", "관련", "대한", "입니다", "있는"
    )
    private val hangulParticleSuffixes = listOf(
        "으로부터", "로부터", "에게서", "한테서", "에서는", "으로는", "로는", "에게는", "한테는",
        "에게", "한테", "에서", "으로", "로", "와의", "과의", "이랑", "랑",
        "와", "과", "은", "는", "이", "가", "을", "를", "의", "도", "만", "까지", "부터"
    ).sortedByDescending { it.length }

    override fun tokenize(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }
        val base = text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(Regex("\\s+"))
            .map { normalizeToken(it) }
            .filter { it.length >= minimumLength(it) }
            .filterNot { stopwords.contains(it) }
            .filterNot { isNoisyToken(it) }

        if (base.isEmpty()) {
            return emptyList()
        }

        val grams = mutableListOf<String>()
        for (size in 2..3) {
            for (index in 0..(base.size - size)) {
                val chunk = base.subList(index, index + size)
                if (chunk.any { token -> stopwords.contains(token) }) {
                    continue
                }
                grams += chunk.joinToString("-")
            }
        }
        return (base + grams).distinct()
    }

    private fun normalizeToken(raw: String): String {
        var token = raw.trim()
        if (token.isBlank()) {
            return ""
        }
        if (!token.any { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HANGUL }) {
            return token
        }

        repeat(2) {
            val suffix = hangulParticleSuffixes.firstOrNull { candidate ->
                token.endsWith(candidate) && token.length - candidate.length >= 2
            } ?: return@repeat
            token = token.removeSuffix(suffix)
        }
        return token
    }

    private fun minimumLength(token: String): Int {
        val hasHangul = token.any { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HANGUL }
        return if (hasHangul) 2 else 3
    }

    private fun isNoisyToken(token: String): Boolean {
        if (token.length > 32) {
            return true
        }
        val digits = token.count { it.isDigit() }
        if (digits == token.length) {
            return true
        }
        if (token.length >= 6 && digits >= token.length / 2) {
            return true
        }
        return false
    }
}

@Component
class NoriAnalyzeTokenizer(
    private val searchProperties: SearchProperties,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry
) : TreeTokenizer {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()
    private val timer = meterRegistry.timer("nori_analyze_latency")
    private val failureCounter = meterRegistry.counter("nori_analyze_fail_total")

    override fun tokenize(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val sample = Timer.start()
        try {
            val payload = objectMapper.writeValueAsString(
                mapOf(
                    "analyzer" to "ko_nori",
                    "text" to text.take(4000)
                )
            )
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(searchProperties.opensearchUrl.trimEnd('/') + "/_analyze"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
            val username = searchProperties.username?.trim().orEmpty()
            val password = searchProperties.password?.trim().orEmpty()
            if (username.isNotEmpty() && password.isNotEmpty()) {
                val token = Base64.getEncoder()
                    .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
                requestBuilder.header("Authorization", "Basic $token")
            }

            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in setOf(200, 201)) {
                throw IllegalStateException("OpenSearch _analyze failed with status=${response.statusCode()}")
            }
            val root = objectMapper.readTree(response.body())
            return root.path("tokens")
                .mapNotNull { tokenNode -> tokenNode.path("token")?.asText() }
                .map { it.lowercase(Locale.ROOT).trim() }
                .filter { it.isNotBlank() }
                .filter { it.length >= 2 }
                .distinct()
        } catch (ex: Exception) {
            failureCounter.increment()
            logger.warn("nori_analyze_failed message={}", ex.message)
            throw ex
        } finally {
            sample.stop(timer)
        }
    }
}

@Component
@Primary
class AdaptiveTreeTokenizer(
    private val featureFlags: FeatureFlags,
    private val searchProperties: SearchProperties,
    private val fallbackTokenizer: FallbackTokenizer,
    private val noriAnalyzeTokenizer: NoriAnalyzeTokenizer,
    meterRegistry: MeterRegistry
) : TreeTokenizer {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val fallbackUsedCounter = meterRegistry.counter("tokenizer_fallback_used_total")

    override fun tokenize(text: String): List<String> {
        if (!featureFlags.noriTokenizer || searchProperties.backend != "opensearch") {
            fallbackUsedCounter.increment()
            return fallbackTokenizer.tokenize(text)
        }
        return runCatching {
            noriAnalyzeTokenizer.tokenize(text)
        }.mapCatching { noriTokens ->
            if (noriTokens.isEmpty()) {
                fallbackUsedCounter.increment()
                return@mapCatching fallbackTokenizer.tokenize(text)
            }
            val fallbackTokens = fallbackTokenizer.tokenize(text)
            (noriTokens + fallbackTokens.filter { it.contains('-') }).distinct()
        }.getOrElse { ex ->
            fallbackUsedCounter.increment()
            logger.debug("nori_tokenizer_fallback message={}", ex.message)
            fallbackTokenizer.tokenize(text)
        }
    }
}

