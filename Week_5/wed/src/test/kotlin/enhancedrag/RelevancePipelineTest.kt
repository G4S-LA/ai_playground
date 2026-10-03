package enhancedrag

import docindex.SearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelevancePipelineTest {
    private val pipeline = RelevancePipeline()

    @Test
    fun `threshold rejects weak candidate and lexical rerank changes order`() {
        val unrelated = hit("unrelated", 0.80f, "Общее описание пользовательского интерфейса")
        val relevant = hit("relevant", 0.70f, "KV Cache: статический префикс сохраняет кэш")
        val weak = hit("weak", 0.30f, "KV Cache и статический префикс")

        val result = pipeline.enhanced(
            hits = listOf(unrelated, relevant, weak),
            question = "Как KV Cache использует статический префикс?",
            threshold = 0.5,
            finalK = 1,
        )

        assertEquals("relevant", result.first().chunkId)
        assertTrue(result.first().selected)
        assertTrue(result.first().rerankScore > result[1].rerankScore)
        assertFalse(result.last().passedThreshold)
        assertFalse(result.last().selected)
    }

    @Test
    fun `baseline keeps vector order and selects final K`() {
        val result = pipeline.baseline(
            hits = listOf(hit("first", 0.9f, "one"), hit("second", 0.8f, "two")),
            question = "query",
            finalK = 1,
        )

        assertEquals(listOf("first", "second"), result.map { it.chunkId })
        assertEquals(listOf(true, false), result.map { it.selected })
        assertTrue(result.all { it.passedThreshold })
    }

    private fun hit(id: String, score: Float, text: String) = SearchHit(
        chunkId = id,
        source = "$id.md",
        title = id,
        section = "Section",
        contentType = "markdown",
        chunkIndex = 0,
        tokenCount = 10,
        page = null,
        text = text,
        score = score,
    )
}
