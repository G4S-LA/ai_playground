package memory

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
    fun `web api exposes invariants and compliance refusal`() = testApplication {
        val agent = MemoryAgent(
            FileMemoryStore(Files.createTempDirectory("invariant-web-test")),
            DemoLanguageModel(),
            DemoInvariantGuard(),
            "system",
            "demo",
        )
        val session = agent.createSession()
        application { memoryModule(agent) }

        val added = client.post("/api/sessions/${session.id}/invariants") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"category":"stack_constraint","content":"Запрещено использовать Python"}""")
        }
        assertEquals(HttpStatusCode.Created, added.status)

        val reply = client.post("/api/sessions/${session.id}/messages") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"message":"Предложи использовать Python"}""")
        }
        assertEquals(HttpStatusCode.OK, reply.status)
        assertTrue(reply.bodyAsText().contains("violation"))
        assertTrue(reply.bodyAsText().contains("Не могу предложить это решение"))

        val snapshot = client.get("/api/sessions/${session.id}")
        assertTrue(snapshot.bodyAsText().contains("Запрещено использовать Python"))
    }
}
