package groundedrag

import enhancedrag.RankedChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroundedAnswerValidatorTest {
    private val validator = GroundedAnswerValidator()
    private val source = RankedChunk(
        chunkId = "chunk-1",
        source = "book.md",
        title = "Book",
        section = "RAG",
        text = "RAG извлекает релевантные фрагменты и добавляет их в контекст модели.",
        similarity = 0.8,
        lexicalScore = 0.7,
        rerankScore = 0.78,
        passedThreshold = true,
        selected = true,
    )

    @Test
    fun `accepts answer with matching source marker and exact quote`() {
        val payload = ModelGroundedPayload(
            answer = "RAG добавляет найденные данные в контекст [S1].",
            sources = listOf(ModelSource("S1", "book.md", "RAG", "chunk-1")),
            quotes = listOf(ModelQuote("S1", "RAG извлекает релевантные фрагменты")),
        )

        val result = validator.validate(payload, listOf(source))

        assertTrue(result.valid)
        assertEquals("book.md", result.sources.single().source)
        assertEquals("RAG извлекает релевантные фрагменты", result.quotes.single().quote)
    }

    @Test
    fun `rejects fabricated quote and inconsistent citation`() {
        val payload = ModelGroundedPayload(
            answer = "Неподтверждённое утверждение [S2].",
            sources = listOf(ModelSource("S1", "book.md", "RAG", "chunk-1")),
            quotes = listOf(ModelQuote("S1", "Такого текста в чанке нет")),
        )

        val result = validator.validate(payload, listOf(source))

        assertFalse(result.valid)
        assertTrue(result.errors.any { "неизвестные ссылки" in it })
        assertTrue(result.errors.any { "не является дословным" in it })
    }
}
