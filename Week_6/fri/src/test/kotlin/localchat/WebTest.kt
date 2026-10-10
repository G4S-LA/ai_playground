package localchat

import com.google.gson.JsonParser
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebTest {
    @Test
    fun `keeps the existing UI and manages persistent dialogs over HTTP`() = testApplication {
        val database = Files.createTempDirectory("private-chat-web").resolve("history.db")
        val service = LocalChatService(ChatRepository(database), FakeChatModel("Привет из модели"))
        application { localChatModule(service, API_KEY) }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("Локальный чат"))
        assertTrue(page.bodyAsText().contains("Новый диалог"))

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/chats").status)

        val createdResponse = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Created, createdResponse.status)
        val chatId = JsonParser.parseString(createdResponse.bodyAsText())
            .asJsonObject.getAsJsonObject("chat").get("id").asString

        val replyResponse = client.post("/api/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            contentType(ContentType.Application.Json)
            setBody("""{"message":"Привет"}""")
        }
        assertEquals(HttpStatusCode.OK, replyResponse.status)
        val messages = JsonParser.parseString(replyResponse.bodyAsText())
            .asJsonObject.getAsJsonObject("chat").getAsJsonArray("messages")
        assertEquals(2, messages.size())

        val chats = client.get("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
        }
        assertEquals(1, JsonParser.parseString(chats.bodyAsText()).asJsonObject.getAsJsonArray("chats").size())

        val deleted = client.delete("/api/chats/$chatId") {
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
        }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
    }

    private companion object {
        const val API_KEY = "a-secure-test-api-key-with-24-chars"
    }
}
