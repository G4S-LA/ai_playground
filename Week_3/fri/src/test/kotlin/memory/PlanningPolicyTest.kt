package memory

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanningPolicyTest {
    private val policy = RequestComplexityPolicy()

    @Test
    fun `simple conversational requests go directly to execution`() {
        assertFalse(policy.needsPlanning("Привет!"))
        assertFalse(policy.needsPlanning("Что такое краткосрочная память?"))
        assertFalse(policy.needsPlanning("Переведи слово memory"))
    }

    @Test
    fun `multi-step and implementation requests require a plan`() {
        assertTrue(policy.needsPlanning("Составь план миграции приложения"))
        assertTrue(policy.needsPlanning("Реализуй CLI и web-интерфейс для агента"))
        assertTrue(policy.needsPlanning("- Добавить API\n- Написать тесты"))
    }

    @Test
    fun `model chooses planning route instead of regex policy`() {
        val model = RouterModel("PLAN")
        val llmPolicy = LlmPlanningPolicy(model)

        assertTrue(llmPolicy.needsPlanning("Привет!"))
        assertTrue(model.lastMessages.first().content.startsWith("[PLANNING_ROUTER]"))
        assertEquals("Привет!", model.lastMessages.last().content)

        model.answer = "DIRECT"
        assertFalse(llmPolicy.needsPlanning("Разработай большую распределённую систему"))
    }

    @Test
    fun `invalid router response falls back to safe complexity policy`() {
        val model = RouterModel("Не уверен")
        val llmPolicy = LlmPlanningPolicy(model)

        assertFalse(llmPolicy.needsPlanning("Привет!"))
        assertTrue(llmPolicy.needsPlanning("Реализуй CLI и web-интерфейс для агента"))

        model.failure = true
        assertTrue(llmPolicy.needsPlanning("Составь подробный план миграции"))
    }
}

private class RouterModel(var answer: String) : LanguageModel {
    var failure: Boolean = false
    var lastMessages: List<PromptMessage> = emptyList()

    override fun complete(messages: List<PromptMessage>): String {
        lastMessages = messages
        if (failure) error("router unavailable")
        return answer
    }
}
