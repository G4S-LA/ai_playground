package enhancedrag

import docindex.DemoEmbeddingProvider
import docindex.DocumentLoader
import docindex.IndexingService
import docindex.SqliteVectorIndex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import ragagent.ChatLanguageModel
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnhancedRagServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `comparison rewrites only enhanced query and limits final context`() = runBlocking {
        val model = RecordingModel()
        val service = service(model, QueryRewriter { RewriteResult("RAG память контекст", 7) })
        service.rebuildStructuredIndex()
        val settings = RetrievalSettings(candidateK = 3, finalK = 1, similarityThreshold = -1.0)

        val result = service.compare("Как работает память агента?", settings)

        assertEquals("Как работает память агента?", result.baseline.searchQuery)
        assertEquals("RAG память контекст", result.enhanced.searchQuery)
        assertEquals(0, result.baseline.rewriteMs)
        assertEquals(7, result.enhanced.rewriteMs)
        assertEquals(1, result.baseline.sources.size)
        assertEquals(1, result.enhanced.sources.size)
        assertTrue(result.enhanced.candidates.size <= settings.candidateK)
        assertEquals(2, model.userPrompts.size)
        assertTrue("[S1]" in model.userPrompts[0])
        assertTrue("RAG память контекст" in model.userPrompts[1])
    }

    @Test
    fun `enhanced mode can answer with no context when threshold removes everything`() = runBlocking {
        val model = RecordingModel()
        val service = service(model, QueryRewriter { RewriteResult("несвязанный запрос", 1) })
        service.rebuildStructuredIndex()

        val result = service.answer(
            "Совсем другой вопрос",
            RetrievalMode.ENHANCED,
            RetrievalSettings(candidateK = 2, finalK = 1, similarityThreshold = 1.0),
        )

        assertTrue(result.sources.isEmpty())
        assertTrue("недостаточно информации" in model.userPrompts.single())
    }

    private fun service(model: ChatLanguageModel, rewriter: QueryRewriter): EnhancedRagService {
        val documents = tempDir.resolve("documents").createDirectories()
        documents.resolve("03-memory-and-rag.md").writeText(
            """
            # Память и RAG

            RAG находит релевантные фрагменты, добавляет их в контекст и передаёт LLM.

            ## Память агента

            Семантическая память хранит обобщённые факты и знания.
            """.trimIndent(),
        )
        val index = IndexingService(
            DocumentLoader(documents),
            DemoEmbeddingProvider(64),
            SqliteVectorIndex(tempDir.resolve("index-${System.nanoTime()}.db")),
        )
        return EnhancedRagService(index, model, rewriter)
    }

    private class RecordingModel : ChatLanguageModel {
        override val description = "recording-model"
        val userPrompts = mutableListOf<String>()
        override suspend fun generate(systemPrompt: String, userPrompt: String): String {
            userPrompts += userPrompt
            return if ("[S1]" in userPrompt) "Ответ по источнику [S1]." else "Недостаточно данных."
        }
    }
}
