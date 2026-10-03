package ragagent

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageModelTest {
    @Test
    fun `OpenAI compatible provider sends bearer token and chat messages`() = runBlocking {
        val authorization = AtomicReference<String>()
        val requestBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = """{"choices":[{"message":{"role":"assistant","content":" Ответ API "}}]}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        try {
            val model = OpenAiCompatibleChatLanguageModel(
                apiKey = "secret-test-key",
                apiUrl = "http://127.0.0.1:${server.address.port}/chat/completions",
                model = "test-model",
            )

            val answer = model.generate("Системная инструкция", "Вопрос пользователя")

            assertEquals("Ответ API", answer)
            assertEquals("Bearer secret-test-key", authorization.get())
            assertTrue(requestBody.get().contains("\"model\":\"test-model\""))
            assertTrue(requestBody.get().contains("\"role\":\"system\""))
            assertTrue(requestBody.get().contains("Системная инструкция"))
            assertTrue(requestBody.get().contains("\"role\":\"user\""))
            assertTrue(requestBody.get().contains("Вопрос пользователя"))
        } finally {
            server.stop(0)
        }
    }
}
