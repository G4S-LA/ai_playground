package localrag

import docindex.DemoEmbeddingProvider
import docindex.DocumentLoader
import docindex.IndexingService
import docindex.SqliteVectorIndex
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalRagServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `retrieves locally and sends only found context to local model`() = runBlocking {
        val documents = tempDir.resolve("documents").createDirectories()
        documents.resolve("rag.md").writeText(
            """
            # RAG pipeline

            Retrieval находит релевантные фрагменты. Augmentation добавляет их в prompt.
            Generation формирует ответ на основании найденного контекста.
            """.trimIndent(),
        )
        val model = FakeLocalChatModel()
        val service = LocalRagService(
            IndexingService(
                DocumentLoader(documents),
                DemoEmbeddingProvider(64),
                SqliteVectorIndex(tempDir.resolve("index.db")),
            ),
            model,
        )
        service.rebuildIndex()

        val result = service.answer("Из каких этапов состоит RAG?", topK = 1)

        assertEquals("RAG объединяет retrieval и generation [S1].", result.answer)
        assertEquals("rag.md", result.sources.single().source)
        assertTrue("[S1]" in model.calls.single().second)
        assertTrue("Retrieval находит" in model.calls.single().second)
        assertTrue("только на основании" in model.calls.single().first)
    }
}
