package localrag

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class OllamaEmbeddingProviderTest {
    @Test
    fun `uses local ollama embed endpoint`() = runBlocking {
        val requestBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/embed") { exchange ->
            requestBody.set(exchange.requestBody.bufferedReader().readText())
            respond(exchange, """{"embeddings":[[0.25,-0.5,0.75]]}""")
        }
        server.start()

        try {
            val provider = LocalOllamaEmbeddingProvider(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                model = "embed-test",
                timeoutSeconds = 5,
            )

            val embedding = provider.embed(listOf("локальный запрос")).single()

            assertContentEquals(floatArrayOf(0.25f, -0.5f, 0.75f), embedding)
            assertTrue(requestBody.get().contains("\"model\":\"embed-test\""))
            assertTrue(requestBody.get().contains("локальный запрос"))
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
