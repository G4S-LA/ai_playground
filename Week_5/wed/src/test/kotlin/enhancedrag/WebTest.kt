package enhancedrag

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
import ragagent.ControlQuestionRepository
import ragagent.DemoChatLanguageModel
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
    fun `web compares baseline and enhanced retrieval`() = testApplication {
        val documents = tempDir.resolve("documents").createDirectories()
        documents.resolve("03-memory-and-rag.md").writeText(
            "# RAG\n\nRAG извлекает релевантные чанки и добавляет их в контекст LLM.",
        )
        val index = IndexingService(
            DocumentLoader(documents),
            DemoEmbeddingProvider(64),
            SqliteVectorIndex(tempDir.resolve("web.db")),
        )
        val service = EnhancedRagService(
            index,
            DemoChatLanguageModel(),
            QueryRewriter { RewriteResult("RAG релевантные чанки", 1) },
        )
        service.rebuildStructuredIndex()
        application { enhancedRagModule(service, ControlQuestionRepository()) }

        assertEquals(HttpStatusCode.OK, client.get("/").status)
        val response = client.post("/api/compare") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"question":"Как работает RAG?","candidateK":3,"finalK":1,"similarityThreshold":-1}""",
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"baseline\""))
        assertTrue(body.contains("\"enhanced\""))
        assertTrue(body.contains("RAG релевантные чанки"))
        assertTrue(body.contains("rerankScore"))
    }
}
