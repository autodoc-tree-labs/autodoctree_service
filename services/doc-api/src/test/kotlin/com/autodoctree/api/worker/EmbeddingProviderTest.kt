package com.autodoctree.api.worker

import com.autodoctree.api.config.EmbeddingInputProperties
import com.autodoctree.api.config.EmbeddingProperties
import com.autodoctree.api.config.OllamaEmbeddingProperties
import com.autodoctree.api.ollama.embedding.OllamaEmbeddingClient
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.SectionRow
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EmbeddingProviderTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun cleanup() {
        servers.forEach { it.stop(0) }
        servers.clear()
    }

    @Test
    fun `input preprocessor generates bounded stable payloads`() {
        val preprocessor = EmbeddingInputPreprocessor(
            embeddingProperties = EmbeddingProperties(
                provider = "stub",
                input = EmbeddingInputProperties(
                    maxChars = 180,
                    headChars = 80,
                    tailChars = 50,
                    sectionHeadingLimit = 3,
                    sectionCountLimit = 2
                ),
                ollama = defaultOllamaProperties("http://localhost:11434")
            )
        )
        val now = LocalDateTime.now()
        val doc = DocumentRow(
            id = "doc-1",
            workspaceId = "ws-1",
            title = "과학 연구 문서",
            bodyMarkdown = "# 본문\n" + "데이터 ".repeat(60),
            bodyText = "데이터 ".repeat(60),
            sourceType = "EDITOR",
            status = "READY",
            version = 1,
            deleted = false,
            createdBy = "u-1",
            createdAt = now,
            updatedAt = now
        )
        val sections = listOf(
            SectionRow("s-1", "ws-1", "doc-1", 1, "개요", "과학 소개 ".repeat(20), null, now),
            SectionRow("s-2", "ws-1", "doc-1", 2, "결과", "연구 결과 ".repeat(20), null, now),
            SectionRow("s-3", "ws-1", "doc-1", 3, "부록", "부록 ".repeat(20), null, now)
        )

        val first = preprocessor.buildPayloads(doc, sections)
        val second = preprocessor.buildPayloads(doc, sections)

        assertEquals(first, second)
        assertEquals(listOf("TITLE", "BODY_SUMMARY", "SECTION", "SECTION"), first.map { it.targetType })
        assertTrue(first.all { it.text.length <= 180 })
        assertTrue(first.first().text.contains("title: 과학 연구 문서"))
    }

    @Test
    fun `ollama provider retries and succeeds`() {
        val attempts = AtomicInteger(0)
        val server = startServer { exchange ->
            attempts.incrementAndGet()
            if (attempts.get() == 1) {
                respond(exchange, 500, """{"error":"temporary"}""")
            } else {
                respond(exchange, 200, """{"embeddings":[[0.1,0.2],[0.3,0.4]]}""")
            }
        }
        val properties = EmbeddingProperties(
            provider = "ollama",
            input = EmbeddingInputProperties(4000, 2400, 1200, 6, 24),
            ollama = defaultOllamaProperties("http://127.0.0.1:${server.address.port}").copy(
                timeoutMs = 2000,
                maxRetries = 1,
                retryBackoffMs = 10,
                batchSize = 8
            )
        )

        val provider = OllamaEmbeddingProvider(
            embeddingProperties = properties,
            ollamaEmbeddingClient = OllamaEmbeddingClient(
                objectMapper = jacksonObjectMapper(),
                embeddingProperties = properties,
                meterRegistry = SimpleMeterRegistry()
            )
        )

        val vectors = provider.embed(listOf("a", "b"))

        assertEquals(2, vectors.size)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `ollama provider opens circuit after threshold`() {
        val attempts = AtomicInteger(0)
        val server = startServer { exchange ->
            attempts.incrementAndGet()
            respond(exchange, 500, """{"error":"down"}""")
        }
        val properties = EmbeddingProperties(
            provider = "ollama",
            input = EmbeddingInputProperties(4000, 2400, 1200, 6, 24),
            ollama = defaultOllamaProperties("http://127.0.0.1:${server.address.port}").copy(
                timeoutMs = 2000,
                maxRetries = 0,
                circuitFailureThreshold = 1,
                circuitOpenMs = 30000
            )
        )

        val provider = OllamaEmbeddingProvider(
            embeddingProperties = properties,
            ollamaEmbeddingClient = OllamaEmbeddingClient(
                objectMapper = jacksonObjectMapper(),
                embeddingProperties = properties,
                meterRegistry = SimpleMeterRegistry()
            )
        )

        assertThrows<IllegalStateException> { provider.embed(listOf("a")) }
        assertThrows<IllegalStateException> { provider.embed(listOf("b")) }
        assertEquals(1, attempts.get())
    }

    private fun defaultOllamaProperties(baseUrl: String): OllamaEmbeddingProperties {
        return OllamaEmbeddingProperties(
            baseUrl = baseUrl,
            model = "bge-m3",
            timeoutMs = 5000,
            batchSize = 32,
            maxRetries = 2,
            retryBackoffMs = 100,
            circuitFailureThreshold = 3,
            circuitOpenMs = 15000
        )
    }

    private fun startServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/embed") { exchange ->
            handler(exchange)
        }
        server.start()
        servers += server
        return server
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
        exchange.responseBody.use { os ->
            os.write(body.toByteArray())
        }
    }
}
