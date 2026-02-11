package com.autodoctree.api.ollama.llm

import com.autodoctree.api.config.LlmProperties
import com.autodoctree.api.config.OllamaLlmProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class OllamaLlmClientTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun cleanup() {
        servers.forEach { it.stop(0) }
        servers.clear()
    }

    @Test
    fun `generate retries and returns response text`() {
        val attempts = AtomicInteger(0)
        val server = startServer { exchange ->
            attempts.incrementAndGet()
            if (attempts.get() == 1) {
                respond(exchange, 500, "{\"error\":\"temporary\"}")
            } else {
                val requestBody = exchange.requestBody.bufferedReader().readText()
                assertTrue(requestBody.contains("\"stream\":false"))
                respond(exchange, 200, "{\"response\":\"라벨 생성 성공\"}")
            }
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val properties = llmProperties(base).copy(
            ollama = llmProperties(base).ollama.copy(
                timeoutMs = 2000,
                maxRetries = 1,
                retryBackoffMs = 10
            )
        )
        val client = OllamaLlmClient(jacksonObjectMapper(), properties, SimpleMeterRegistry())

        val result = client.generate("테스트 프롬프트")

        assertEquals("라벨 생성 성공", result)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `generate opens circuit after repeated failures`() {
        val attempts = AtomicInteger(0)
        val server = startServer { exchange ->
            attempts.incrementAndGet()
            respond(exchange, 500, "{\"error\":\"down\"}")
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val properties = llmProperties(base).copy(
            ollama = llmProperties(base).ollama.copy(
                timeoutMs = 2000,
                maxRetries = 0,
                circuitFailureThreshold = 1,
                circuitOpenMs = 30000
            )
        )
        val client = OllamaLlmClient(jacksonObjectMapper(), properties, SimpleMeterRegistry())

        assertThrows<IllegalStateException> { client.generate("a") }
        assertThrows<IllegalStateException> { client.generate("b") }
        assertEquals(1, attempts.get())
    }

    private fun llmProperties(baseUrl: String): LlmProperties {
        return LlmProperties(
            provider = "ollama",
            ollama = OllamaLlmProperties(
                baseUrl = baseUrl,
                model = "llama3.1:8b-instruct",
                timeoutMs = 5000,
                maxRetries = 2,
                retryBackoffMs = 100,
                circuitFailureThreshold = 3,
                circuitOpenMs = 15000
            )
        )
    }

    private fun startServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/generate") { exchange ->
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
