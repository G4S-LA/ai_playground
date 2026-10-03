package groundedrag

import enhancedrag.RetrievalSettings
import kotlinx.coroutines.runBlocking
import ragagent.ChatLanguageModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticSupportJudgeTest {
    @Test
    fun `parses semantic support decision from judge`() = runBlocking {
        val model = object : ChatLanguageModel {
            override val description = "judge"
            override suspend fun generate(systemPrompt: String, userPrompt: String) =
                """{"supported":true,"reason":"Цитата прямо подтверждает утверждение."}"""
        }
        val result = answer(needsClarification = false)

        val assessment = SemanticSupportJudge(model).assess(result)

        assertTrue(assessment.supported)
        assertEquals("llm-judge", assessment.method)
    }

    @Test
    fun `unknown answer is unsupported without judge call`() = runBlocking {
        var calls = 0
        val model = object : ChatLanguageModel {
            override val description = "judge"
            override suspend fun generate(systemPrompt: String, userPrompt: String): String {
                calls++
                return "{}"
            }
        }

        val assessment = SemanticSupportJudge(model).assess(answer(needsClarification = true))

        assertEquals(0, calls)
        assertEquals("deterministic", assessment.method)
    }

    private fun answer(needsClarification: Boolean) = GroundedAnswer(
        question = "question",
        searchQuery = "query",
        answer = if (needsClarification) "Не знаю" else "RAG использует контекст [S1].",
        sources = if (needsClarification) emptyList() else listOf(GroundedSource("S1", "a.md", "A", "id", .8, .8)),
        quotes = if (needsClarification) emptyList() else listOf(VerifiedQuote("S1", "RAG использует контекст")),
        candidates = emptyList(),
        settings = RetrievalSettings(),
        needsClarification = needsClarification,
        clarificationPrompt = null,
        validation = AnswerValidation(true, emptyList(), 1, false),
        rewriteMs = 1,
        elapsedMs = 2,
    )
}
