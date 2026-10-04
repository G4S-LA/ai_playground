package docindex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkingTest {
    @Test
    fun `fixed chunker uses exact window and overlap`() {
        val document = SourceDocument("book.txt", "Book", ContentType.TEXT, "x".repeat(450))

        val chunks = FixedSizeChunker(chunkSize = 200, overlap = 50).chunk(document)

        assertEquals(3, chunks.size)
        assertEquals(listOf(0, 150, 300), chunks.map { it.startPosition })
        assertEquals(listOf(200, 350, 450), chunks.map { it.endPosition })
        assertTrue(chunks.all { it.strategy == ChunkStrategy.FIXED })
    }

    @Test
    fun `structured markdown chunker preserves heading path`() {
        val document = SourceDocument(
            source = "guide.md",
            title = "Guide",
            contentType = ContentType.MARKDOWN,
            text = """
                # Installation
                Common instructions.

                ## Linux
                Use the package manager.

                ## macOS
                Use Homebrew.
            """.trimIndent(),
        )

        val chunks = StructuredChunker(maxChunkSize = 500).chunk(document)

        assertEquals(3, chunks.size)
        assertEquals("Installation", chunks[0].section)
        assertEquals("Installation > Linux", chunks[1].section)
        assertEquals("Installation > macOS", chunks[2].section)
        assertTrue(chunks.all { it.chunkId.startsWith("structured:") })
    }

    @Test
    fun `structured code chunker separates top level declarations`() {
        val document = SourceDocument(
            source = "Example.kt",
            title = "Example",
            contentType = ContentType.CODE,
            text = """
                package sample

                class Greeter {
                    fun hello() = "hello"
                }

                fun main() = println(Greeter().hello())
            """.trimIndent(),
        )

        val chunks = StructuredChunker(maxChunkSize = 500).chunk(document)

        assertEquals(listOf("Файл и импорты", "class Greeter", "fun main"), chunks.map { it.section })
    }

    @Test
    fun `structured chunker does not start oversized parts in the middle of a word`() {
        val text = "# Большой раздел\n\n" + (1..180).joinToString(" ") { "термин$it" }
        val document = SourceDocument("book.md", "Book", ContentType.MARKDOWN, text)

        val chunks = StructuredChunker(maxChunkSize = 300, fallbackOverlap = 60).chunk(document)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.drop(1).all { document.text[it.startPosition - 1].isWhitespace() })
        assertTrue(chunks.dropLast(1).all { document.text[it.endPosition].isWhitespace() })
    }
}
