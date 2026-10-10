package localopt

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OllamaClientTest {
    @Test
    fun `sends optimized options and reads native ollama metrics`() = runBlocking {
        val chatBody = AtomicReference<String>()
        val authorization = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tags") { exchange ->
            respond(
                exchange,
                """{"models":[{"name":"qwen-test:latest","size":1900000000,"details":{"parameter_size":"3.1B","quantization_level":"Q4_K_M"}}]}""",
            )
        }
        server.createContext("/api/chat") { exchange ->
            chatBody.set(exchange.requestBody.bufferedReader().readText())
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            respond(
                exchange,
                """{"message":{"role":"assistant","content":"Локальный ответ"},"total_duration":2500000000,"load_duration":100000000,"prompt_eval_count":30,"prompt_eval_duration":200000000,"eval_count":100,"eval_duration":2000000000}""",
            )
        }
        server.createContext("/api/ps") { exchange ->
            respond(
                exchange,
                """{"models":[{"name":"qwen-test:latest","size":2100000000,"size_vram":1800000000,"context_length":4096}]}""",
            )
        }
        server.start()

        try {
            val client = OllamaClient("http://127.0.0.1:${server.address.port}", "qwen-test", 5)
            val info = client.info()
            val run = client.generate("Вопрос", optimized())

            assertTrue(info.reachable)
            assertTrue(info.installed)
            assertEquals("Q4_K_M", info.quantization)
            assertEquals("Локальный ответ", run.answer)
            assertEquals(2500, run.metrics.totalMs)
            assertEquals(50.0, run.metrics.tokensPerSecond)
            assertEquals(1_800_000_000, run.metrics.resources.vramBytes)
            assertNull(authorization.get())
            assertTrue(chatBody.get().contains("\"num_predict\":384"))
            assertTrue(chatBody.get().contains("\"num_ctx\":4096"))
            assertTrue(chatBody.get().contains("\"temperature\":0.1"))

            client.generate("Вопрос без инструкций", baseline().copy(systemPrompt = ""))
            assertFalse(chatBody.get().contains("\"role\":\"system\""))
            assertTrue(chatBody.get().contains("\"role\":\"user\""))
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
