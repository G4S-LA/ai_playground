package localchat

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OllamaChatModelTest {
    @Test
    fun `uses native local ollama http api`() = runBlocking {
        val receivedBodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tags") { exchange ->
            respond(exchange, """{"models":[{"name":"qwen-test:latest"}]}""")
        }
        server.createContext("/api/chat") { exchange ->
            receivedBodies += exchange.requestBody.bufferedReader().readText()
            respond(exchange, """{"message":{"role":"assistant","content":"4"},"done":true}""")
        }
        server.start()

        try {
            val model = OllamaChatModel(
                endpoint = "http://127.0.0.1:${server.address.port}",
                modelName = "qwen-test",
                temperature = 0.2,
                timeoutSeconds = 5,
            )

            val status = model.status()
            val answer = model.complete(listOf(ChatMessage("user", "Сколько будет 2 + 2?")))

            assertTrue(status.reachable)
            assertTrue(status.installed)
            assertEquals("4", answer)
            assertTrue(receivedBodies.single().contains("\"stream\":false"))
            assertTrue(receivedBodies.single().contains("Сколько будет 2 + 2?"))
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
