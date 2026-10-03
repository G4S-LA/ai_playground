package groundedrag

import com.google.gson.Gson
import docindex.DemoEmbeddingProvider
import docindex.DocumentLoader
import docindex.IndexingService
import docindex.SqliteVectorIndex
import enhancedrag.QueryRewriter
import enhancedrag.RewriteResult
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import ragagent.ChatLanguageModel
import ragagent.ControlQuestionRepository
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
    fun `API returns answer sources quotes and validation`() = testApplication {
        val documents = tempDir.resolve("documents").createDirectories()
        documents.resolve("03-memory-and-rag.md").writeText(
            "# RAG\n\nRAG извлекает релевантные фрагменты и добавляет их в контекст модели.",
        )
        val index = IndexingService(
            DocumentLoader(documents),
            DemoEmbeddingProvider(64),
            SqliteVectorIndex(tempDir.resolve("web.db")),
        )
        val model = object : ChatLanguageModel {
            override val description = "web-model"
            override suspend fun generate(systemPrompt: String, userPrompt: String): String {
                val metadata = requireNotNull(
                    Regex("\\[S1] source=([^;]+); section=([^;]+); chunk_id=([^\\n]+)").find(userPrompt),
                )
                return Gson().toJson(
                    ModelGroundedPayload(
                        answer = "RAG извлекает фрагменты [S1].",
                        sources = listOf(ModelSource("S1", metadata.groupValues[1], metadata.groupValues[2], metadata.groupValues[3])),
                        quotes = listOf(ModelQuote("S1", "RAG извлекает релевантные фрагменты")),
                    ),
                )
            }
        }
        val service = GroundedRagService(index, model, QueryRewriter { RewriteResult("RAG фрагменты", 1) })
        service.rebuildStructuredIndex()
        application { groundedRagModule(service, SemanticSupportJudge(model), ControlQuestionRepository()) }

        assertEquals(HttpStatusCode.OK, client.get("/").status)
        val response = client.post("/api/answer") {
            contentType(ContentType.Application.Json)
            setBody("""{"question":"Как работает RAG?","candidateK":3,"finalK":1,"similarityThreshold":-1}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"sources\""))
        assertTrue(body.contains("\"quotes\""))
        assertTrue(body.contains("\"chunk_id\""))
        assertTrue(body.contains("\"valid\": true"))
    }
}
