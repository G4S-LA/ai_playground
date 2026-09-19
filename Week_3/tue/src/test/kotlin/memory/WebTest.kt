package memory

import com.google.gson.Gson
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
        assertTrue(page.bodyAsText().contains("Профиль пользователя"))

        val categories = client.get("/api/categories")
        assertEquals(HttpStatusCode.OK, categories.status)

        val invalidProfile = client.post("/api/profiles") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidProfile.status)

        val remembered = client.post("/api/sessions/${session.id}/memories") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"layer":"working","category":"goal","content":"Показать память"}""")
        }
        assertEquals(HttpStatusCode.Created, remembered.status)

        val createdProfileResponse = client.post("/api/profiles") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                """{"name":"Эксперт","style":"Технический","format":"Краткий Markdown","constraints":["Без воды"]}"""
            )
        }
        assertEquals(HttpStatusCode.Created, createdProfileResponse.status)
        val profile = Gson().fromJson(
            createdProfileResponse.bodyAsText(),
            ProfileResponse::class.java,
        ).profile

        val selected = client.put("/api/sessions/${session.id}/profile") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"profileId":"${profile.id}"}""")
        }
        assertEquals(HttpStatusCode.OK, selected.status)
        assertTrue(selected.bodyAsText().contains("Эксперт"))

        val reply = client.post("/api/sessions/${session.id}/messages") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"message":"Что в памяти?"}""")
        }
        assertEquals(HttpStatusCode.OK, reply.status)
        assertTrue(reply.bodyAsText().contains("Профиль «Эксперт»"))

        val snapshot = client.get("/api/sessions/${session.id}")
        assertEquals(HttpStatusCode.OK, snapshot.status)
        assertTrue(snapshot.bodyAsText().contains("Показать память"))
        assertTrue(snapshot.bodyAsText().contains("Что в памяти?"))
        assertTrue(snapshot.bodyAsText().contains("Технический"))

        val deleted = client.delete("/api/sessions/${session.id}")
        assertEquals(HttpStatusCode.OK, deleted.status)
    }
}
