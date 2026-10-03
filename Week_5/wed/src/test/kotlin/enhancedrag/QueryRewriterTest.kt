package enhancedrag

import kotlinx.coroutines.runBlocking
import ragagent.ChatLanguageModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryRewriterTest {
    @Test
    fun `LLM rewriter requests search query and normalizes response`() = runBlocking {
        val model = object : ChatLanguageModel {
            override val description = "fake"
            var system = ""
            override suspend fun generate(systemPrompt: String, userPrompt: String): String {
                system = systemPrompt
                return "\"KV Cache статический\nпрефикс\""
            }
        }

        val result = LlmQueryRewriter(model).rewrite("Как работает кэш?")

        assertEquals("KV Cache статический префикс", result.query)
        assertTrue("Не отвечай" in model.system)
    }
}
