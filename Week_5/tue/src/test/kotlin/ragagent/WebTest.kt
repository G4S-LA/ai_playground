package ragagent

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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `web exposes control set and comparison`() = testApplication {
        val service = createService()
        service.rebuildStructuredIndex()
        application { ragWebModule(service, ControlQuestionRepository()) }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)

        val questions = client.get("/api/questions")
        assertEquals(HttpStatusCode.OK, questions.status)
        assertTrue(questions.bodyAsText().contains("агентный RAG"))

        val comparison = client.post("/api/compare") {
            contentType(ContentType.Application.Json)
            setBody("""{"question":"Что такое RAG?","topK":1}""")
        }
        assertEquals(HttpStatusCode.OK, comparison.status)
        val body = comparison.bodyAsText()
        assertTrue(body.contains("without_rag"))
        assertTrue(body.contains("with_rag"))
        assertTrue(body.contains("03-memory-and-rag.md"))
    }

    private fun createService(): RagAgentService {
        val documents = tempDir.resolve("documents").createDirectories()
        documents.resolve("03-memory-and-rag.md").writeText(
            """
            # Память и RAG

            RAG находит релевантные чанки, добавляет их в контекст и передаёт LLM для генерации ответа.
            """.trimIndent(),
        )
        val indexing = IndexingService(
            DocumentLoader(documents),
            DemoEmbeddingProvider(64),
            SqliteVectorIndex(tempDir.resolve("web-index.db")),
        )
        return RagAgentService(indexing, DemoChatLanguageModel())
    }
}
