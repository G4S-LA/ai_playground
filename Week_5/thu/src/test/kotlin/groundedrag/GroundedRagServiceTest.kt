package groundedrag

import com.google.gson.Gson
import docindex.DemoEmbeddingProvider
import docindex.DocumentLoader
import docindex.IndexingService
import docindex.SqliteVectorIndex
import enhancedrag.QueryRewriter
import enhancedrag.RetrievalSettings
import enhancedrag.RewriteResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import ragagent.ChatLanguageModel
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroundedRagServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `returns mandatory metadata and exact quote for valid model JSON`() = runBlocking {
        val model = ValidGroundedModel()
        val service = service(model)
        service.rebuildStructuredIndex()

        val result = service.answer("Как работает RAG?", settings(threshold = -1.0))

        assertFalse(result.needsClarification)
        assertEquals("03-memory-and-rag.md", result.sources.single().source)
        assertEquals("RAG извлекает релевантные фрагменты", result.quotes.single().quote)
        assertTrue(result.validation.valid)
        assertFalse(result.validation.usedFallback)
        assertEquals(1, model.calls)
    }

    @Test
    fun `returns deterministic unknown without answer generation below threshold`() = runBlocking {
        val model = QueueModel()
        val service = service(model)
        service.rebuildStructuredIndex()

        val result = service.answer("Совсем посторонний вопрос", settings(threshold = 1.0))

        assertTrue(result.needsClarification)
        assertTrue(result.answer.startsWith("Не знаю:"))
        assertTrue(result.sources.isEmpty())
        assertTrue(result.quotes.isEmpty())
        assertEquals(0, model.calls)
    }

    @Test
    fun `uses exact safe fallback after two invalid model responses`() = runBlocking {
        val model = QueueModel(
            """{"answer":null,"sources":null,"quotes":{}}""",
            "still not json",
        )
        val service = service(model)
        service.rebuildStructuredIndex()

        val result = service.answer("Как работает RAG?", settings(threshold = -1.0))

        assertTrue(result.validation.usedFallback)
        assertEquals(2, result.validation.attempts)
        assertEquals(2, model.calls)
        assertTrue(result.quotes.single().quote in documentText())
        assertTrue("[S1]" in result.answer)
    }

    private fun service(model: ChatLanguageModel): GroundedRagService {
        val documents = tempDir.resolve("documents").createDirectories()
        documents.resolve("03-memory-and-rag.md").writeText(documentText())
        val index = IndexingService(
            DocumentLoader(documents),
            DemoEmbeddingProvider(64),
            SqliteVectorIndex(tempDir.resolve("index-${System.nanoTime()}.db")),
        )
        val rewriter = QueryRewriter { RewriteResult("RAG релевантные фрагменты контекст", 2) }
        return GroundedRagService(index, model, rewriter)
    }

    private fun settings(threshold: Double) = RetrievalSettings(candidateK = 3, finalK = 1, similarityThreshold = threshold)

    private fun documentText() = """
        # Память и RAG

        RAG извлекает релевантные фрагменты и добавляет их в контекст модели.
        Ответ генерируется только после получения подходящих данных.
    """.trimIndent()

    private class QueueModel(vararg responses: String) : ChatLanguageModel {
        private val responses = responses.toMutableList()
        override val description = "queue-model"
        var calls = 0
        override suspend fun generate(systemPrompt: String, userPrompt: String): String {
            calls++
            return responses.removeFirst()
        }
    }

    private class ValidGroundedModel : ChatLanguageModel {
        override val description = "valid-grounded-model"
        var calls = 0
        override suspend fun generate(systemPrompt: String, userPrompt: String): String {
            calls++
            val metadata = requireNotNull(SOURCE_HEADER.find(userPrompt))
            return Gson().toJson(
                ModelGroundedPayload(
                    answer = "RAG использует найденные фрагменты [S1].",
                    sources = listOf(
                        ModelSource(
                            citation = "S1",
                            source = metadata.groupValues[1],
                            section = metadata.groupValues[2],
                            chunkId = metadata.groupValues[3],
                        ),
                    ),
                    quotes = listOf(ModelQuote("S1", "RAG извлекает релевантные фрагменты")),
                ),
            )
        }

        private companion object {
            val SOURCE_HEADER = Regex("\\[S1] source=([^;]+); section=([^;]+); chunk_id=([^\\n]+)")
        }
    }
}
