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
    fun `web interface exposes optimistic feedback and scroll containment`() = testApplication {
        val agent = MemoryAgent(
            FileMemoryStore(Files.createTempDirectory("invariant-web-assets-test")),
            DemoLanguageModel(),
            DemoInvariantGuard(),
            "system",
            "demo",
        )
        application { memoryModule(agent) }

        val script = client.get("/static/app.js")
        assertEquals(HttpStatusCode.OK, script.status)
        assertTrue(script.bodyAsText().contains("Формирую ответ и проверяю инварианты"))
        assertTrue(script.bodyAsText().contains("message--pending"))

        val styles = client.get("/static/styles.css")
        assertEquals(HttpStatusCode.OK, styles.status)
        assertTrue(styles.bodyAsText().contains("overscroll-behavior: contain"))
        assertTrue(styles.bodyAsText().contains(".chat { min-width: 0; min-height: 0"))
    }

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
