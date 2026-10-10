package localchat

import com.google.gson.JsonParser
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebTest {
    @Test
    fun `web api creates chats sends messages and deletes persisted history`() = testApplication {
        val database = Files.createTempDirectory("local-chat-web").resolve("history.db")
        val service = LocalChatService(ChatRepository(database), FakeChatModel("Четыре"), "Система")
        application { localChatModule(service) }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("Новый диалог"))

        val create = client.post("/api/chats") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Created, create.status)
        val id = JsonParser.parseString(create.bodyAsText()).asJsonObject
            .getAsJsonObject("chat").get("id").asString

        val send = client.post("/api/chats/$id/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"Сколько будет два плюс два?"}""")
        }
        assertEquals(HttpStatusCode.OK, send.status)
        val reply = JsonParser.parseString(send.bodyAsText()).asJsonObject
        assertEquals("Четыре", reply.getAsJsonObject("message").get("content").asString)

        val list = client.get("/api/chats")
        val summary = JsonParser.parseString(list.bodyAsText()).asJsonObject
            .getAsJsonArray("chats").single().asJsonObject
        assertEquals(2, summary.get("messageCount").asInt)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/chats/$id").status)
        val emptyList = JsonParser.parseString(client.get("/api/chats").bodyAsText())
            .asJsonObject.getAsJsonArray("chats")
        assertTrue(emptyList.isEmpty)
    }

    @Test
    fun `reports local model availability`() = testApplication {
        val database = Files.createTempDirectory("local-chat-info").resolve("history.db")
        application {
            localChatModule(LocalChatService(ChatRepository(database), FakeChatModel(), "Система"))
        }

        val response = client.get("/api/info")

        assertEquals(HttpStatusCode.OK, response.status)
        val status = JsonParser.parseString(response.bodyAsText()).asJsonObject.getAsJsonObject("status")
        assertTrue(status.get("reachable").asBoolean)
        assertTrue(status.get("installed").asBoolean)
    }
}
