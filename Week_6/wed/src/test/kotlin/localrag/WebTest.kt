package localrag

import com.google.gson.JsonParser
import docindex.DemoEmbeddingProvider
import docindex.DocumentLoader
import docindex.IndexingService
import docindex.SqliteVectorIndex
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebTest {
    @Test
    fun `web api exposes local status index and grounded answer`() = testApplication {
        val root = Files.createTempDirectory("local-rag-web")
        val documents = root.resolve("documents").createDirectories()
        documents.resolve("knowledge.md").writeText(
            "# Local RAG\n\nRetrieval и генерация выполняются на одном компьютере.",
        )
        val service = LocalRagService(
            IndexingService(
                DocumentLoader(documents),
                DemoEmbeddingProvider(48),
                SqliteVectorIndex(root.resolve("index.db")),
            ),
            FakeLocalChatModel("Полностью локально [S1]."),
        )
        service.rebuildIndex()
        val config = LocalRagConfig.load(
            environment = mapOf(
                "DOCUMENTS_DIR" to documents.toString(),
                "INDEX_DB" to root.resolve("index.db").toString(),
                "OLLAMA_EMBEDDING_MODEL" to "test-embed",
                "OLLAMA_CHAT_MODEL" to "test-local",
            ),
            workingDirectory = root,
        )
        application { localRagModule(service, config) }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("<title>Local RAG · Answers under evidence</title>"))
        assertTrue(html.contains("Спросить у локального RAG"))
        val info = JsonParser.parseString(client.get("/api/info").bodyAsText()).asJsonObject
        assertTrue(info.getAsJsonObject("status").get("reachable").asBoolean)
        assertTrue(info.get("chatModelInstalled").asBoolean)
        assertTrue(info.get("embeddingModelInstalled").asBoolean)

        val response = client.post("/api/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"question":"Где работает RAG?","topK":1}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val answer = JsonParser.parseString(response.bodyAsText()).asJsonObject
        assertEquals("Полностью локально [S1].", answer.get("answer").asString)
        assertEquals("knowledge.md", answer.getAsJsonArray("sources").single().asJsonObject.get("source").asString)
    }
}
