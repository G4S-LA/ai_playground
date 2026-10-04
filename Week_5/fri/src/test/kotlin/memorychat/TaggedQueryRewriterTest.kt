package memorychat

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TaggedQueryRewriterTest {
    @Test
    fun `extracts standalone query from conversation context`() = runBlocking {
        val result = TaggedQueryRewriter().rewrite(
            """
            <search_query>память задачи RAG-агента</search_query>
            Текущая реплика: а это как хранить?
            """.trimIndent(),
        )

        assertEquals("память задачи RAG-агента", result.query)
        assertEquals(0, result.elapsedMs)
    }
}
