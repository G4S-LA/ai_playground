package memory

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebTest {
    @Test
    fun `web api creates dialogue remembers explicitly and replies`() = testApplication {
        val store = FileMemoryStore(Files.createTempDirectory("memory-web-test"))
        val agent = MemoryAgent(store, DemoLanguageModel(), "system", "demo")
        val session = agent.createSession()
        application { memoryModule(agent) }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("Контролируемый автомат"))

        val categories = client.get("/api/categories")
        assertEquals(HttpStatusCode.OK, categories.status)

        val remembered = client.post("/api/sessions/${session.id}/memories") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"layer":"working","category":"goal","content":"Показать память"}""")
        }
        assertEquals(HttpStatusCode.Created, remembered.status)

        val reply = client.post("/api/sessions/${session.id}/messages") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"message":"Реализуй CLI и web-интерфейс для агента"}""")
        }
        assertEquals(HttpStatusCode.OK, reply.status)
        val replyBody = reply.bodyAsText()
        for (stage in listOf("planning", "execution", "validation", "done")) {
            assertTrue(replyBody.contains("\"stage\": \"$stage\""), replyBody)
        }
        assertTrue(replyBody.contains("\"planningApplied\": true"), replyBody)
        assertTrue(replyBody.contains("\"planApproved\": true"), replyBody)

        val snapshot = client.get("/api/sessions/${session.id}")
        assertEquals(HttpStatusCode.OK, snapshot.status)
        val snapshotBody = snapshot.bodyAsText()
        assertTrue(snapshotBody.contains("Показать память"))
        assertTrue(snapshotBody.contains("Реализуй CLI и web-интерфейс"))
        assertTrue(snapshotBody.contains("Ответ проверен и готов"))
        assertTrue(snapshotBody.contains("agent_plan"))

        val deleted = client.delete("/api/sessions/${session.id}")
        assertEquals(HttpStatusCode.OK, deleted.status)
    }
}
