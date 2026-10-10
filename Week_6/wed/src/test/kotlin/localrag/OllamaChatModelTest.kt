package localrag

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OllamaChatModelTest {
    @Test
    fun `uses local ollama tags and chat endpoints without cloud authorization`() = runBlocking {
        val chatBody = AtomicReference<String>()
        val authorization = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tags") { exchange ->
            respond(exchange, """{"models":[{"name":"qwen-test:latest"},{"name":"embed-test:latest"}]}""")
        }
        server.createContext("/api/chat") { exchange ->
            chatBody.set(exchange.requestBody.bufferedReader().readText())
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, """{"message":{"role":"assistant","content":"Локальный ответ [S1]"}}""")
        }
        server.start()

        try {
            val model = OllamaChatModel(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                model = "qwen-test",
                temperature = 0.1,
                timeoutSeconds = 5,
            )

            val status = model.status()
            val answer = model.generate("Отвечай по контексту", "Контекст [S1]")

            assertTrue(status.reachable)
            assertTrue(status.hasModel("qwen-test"))
            assertEquals("Локальный ответ [S1]", answer)
            assertNull(authorization.get())
            assertTrue(chatBody.get().contains("\"stream\":false"))
            assertTrue(chatBody.get().contains("Контекст [S1]"))
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
