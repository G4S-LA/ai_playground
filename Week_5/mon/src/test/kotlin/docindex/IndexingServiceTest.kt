package docindex

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexingServiceTest {
    @Test
    fun `build stores metadata and search returns nearest chunks`() = runBlocking {
        val root = createTempDirectory("document-index-test")
        val documents = Files.createDirectories(root.resolve("documents"))
        documents.resolve("guide.md").writeText(
            """
            # Local launch
            Start the server with Gradle. The application listens on port 8080.

            # Database
            SQLite stores chunks, metadata and embedding vectors.
            """.trimIndent(),
        )
        val service = IndexingService(
            loader = DocumentLoader(documents),
            embeddings = DemoEmbeddingProvider(),
            index = SqliteVectorIndex(root.resolve("index.db")),
        )

        val fixed = service.build(ChunkStrategy.FIXED)
        val structured = service.build(ChunkStrategy.STRUCTURED)
        val hits = service.search("SQLite metadata vectors", ChunkStrategy.STRUCTURED, topK = 2)

        assertEquals(1, fixed.documents)
        assertEquals(2, structured.chunks)
        assertEquals("Database", hits.first().section)
        assertEquals("guide.md", hits.first().source)
        assertTrue(hits.first().chunkId.startsWith("structured:"))
        assertEquals(listOf(1, 1), service.stats().map { it.documents })
    }
}
