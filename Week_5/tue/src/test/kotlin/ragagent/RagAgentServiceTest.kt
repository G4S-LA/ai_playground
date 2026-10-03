package ragagent

import docindex.ChunkStrategy
import docindex.DemoEmbeddingProvider
import docindex.DocumentLoader
import docindex.IndexingService
import docindex.SqliteVectorIndex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RagAgentServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `comparison sends retrieved context only to RAG mode`() = runBlocking {
        val model = RecordingModel()
        val service = service(model)
        val build = service.rebuildStructuredIndex()

        val result = service.compare("Из каких компонентов состоит агент?", topK = 2)

        assertEquals(ChunkStrategy.STRUCTURED.wireName, build.strategy)
        assertEquals(2, model.calls.size)
        assertFalse("[S1]" in model.calls[0].second)
        assertTrue("[S1]" in model.calls[1].second)
        assertTrue("00-introduction.md" in model.calls[1].second)
        assertTrue(result.withoutRag.sources.isEmpty())
        assertTrue(result.withRag.sources.isNotEmpty())
        assertEquals("00-introduction.md", result.withRag.sources.first().source)
    }

    @Test
    fun `empty question is rejected before model call`() = runBlocking {
        val model = RecordingModel()
        val service = service(model)

        val error = runCatching { service.answer("   ", AnswerMode.WITHOUT_RAG) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(model.calls.isEmpty())
    }

    private fun service(model: ChatLanguageModel): RagAgentService {
        val documents = tempDir.resolve("documents").createDirectories()
        documents.resolve("00-introduction.md").writeText(
            """
            # Введение

            Агент состоит из LLM, контекста и инструментов. LLM — мозг агента,
            контекст задаёт доступную информацию, инструменты позволяют действовать.
            """.trimIndent(),
        )
        val indexing = IndexingService(
            DocumentLoader(documents),
            DemoEmbeddingProvider(64),
            SqliteVectorIndex(tempDir.resolve("index.db")),
        )
        return RagAgentService(indexing, model)
    }

    private class RecordingModel : ChatLanguageModel {
        val calls = mutableListOf<Pair<String, String>>()
        override val description = "test-model"

        override suspend fun generate(systemPrompt: String, userPrompt: String): String {
            calls += systemPrompt to userPrompt
            return if ("[S1]" in userPrompt) "LLM, контекст и инструменты [S1]." else "Ответ модели."
        }
    }
}
