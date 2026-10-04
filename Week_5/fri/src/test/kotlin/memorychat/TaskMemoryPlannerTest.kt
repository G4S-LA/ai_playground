package memorychat

import kotlinx.coroutines.runBlocking
import ragagent.ChatLanguageModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskMemoryPlannerTest {
    @Test
    fun `parses structured memory update`() = runBlocking {
        val model = QueueModel(
            """{"goal":"Спроектировать RAG-агента","clarified_facts":["База на русском"],"constraints":["Только локальные embeddings"],"terms":[{"term":"память","meaning":"task state"}],"standalone_question":"Как хранить task state RAG-агента?"}""",
        )
        val result = LlmTaskMemoryPlanner(model).update(TaskMemory(), emptyList(), "Как хранить память?")

        assertEquals("Спроектировать RAG-агента", result.memory.goal)
        assertEquals(listOf("Только локальные embeddings"), result.memory.constraints)
        assertEquals(MemoryTerm("память", "task state"), result.memory.terms.single())
        assertEquals("Как хранить task state RAG-агента?", result.standaloneQuestion)
        assertEquals("llm", result.memory.updateMethod)
    }

    @Test
    fun `keeps previous state when model response is invalid`() = runBlocking {
        val previous = TaskMemory(
            goal = "Сохранить цель",
            constraints = listOf("Ответы с источниками"),
            revision = 4,
        )
        val result = LlmTaskMemoryPlanner(QueueModel("not json")).update(previous, emptyList(), "А второй вариант?")

        assertEquals("Сохранить цель", result.memory.goal)
        assertEquals(listOf("Ответы с источниками"), result.memory.constraints)
        assertEquals(5, result.memory.revision)
        assertEquals("fallback", result.memory.updateMethod)
        assertTrue("Сохранить цель" in result.standaloneQuestion)
    }

    private class QueueModel(vararg responses: String) : ChatLanguageModel {
        private val queue = ArrayDeque(responses.toList())
        override val description = "test"
        override suspend fun generate(systemPrompt: String, userPrompt: String): String = queue.removeFirst()
    }
}
