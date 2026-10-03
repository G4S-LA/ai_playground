package ragagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QualityEvaluatorTest {
    private val control = ControlQuestion(
        id = 1,
        question = "Из чего состоит агент?",
        expectation = "LLM, контекст и инструменты",
        expectedSources = listOf("00-introduction.md"),
        requiredTerms = listOf("LLM", "контекст", "инструмент"),
    )

    @Test
    fun `RAG assessment detects terms expected source and citation`() {
        val answer = AgentAnswer(
            mode = AnswerMode.WITH_RAG.wireName,
            question = control.question,
            answer = "Агент объединяет LLM, контекст и инструменты [S1].",
            sources = listOf(
                RagSource("S1", "id", "folder/00-introduction.md", "Введение", 0.9f, "text"),
            ),
            elapsedMs = 1,
        )

        val result = QualityEvaluator().evaluate(answer, control)

        assertEquals(1.0, result.conceptCoverage)
        assertEquals(1.0, result.sourceRecall)
        assertTrue(result.hasCitations)
        assertTrue(result.missingTerms.isEmpty())
    }

    @Test
    fun `baseline has no source metric`() {
        val answer = AgentAnswer(
            mode = AnswerMode.WITHOUT_RAG.wireName,
            question = control.question,
            answer = "LLM отвечает на вопрос.",
            sources = emptyList(),
            elapsedMs = 1,
        )

        val result = QualityEvaluator().evaluate(answer, control)

        assertEquals(null, result.sourceRecall)
        assertFalse(result.hasCitations)
        assertTrue(result.conceptCoverage < 1.0)
    }
}
