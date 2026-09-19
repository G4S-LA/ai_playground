package memory

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryAgentTest {
    @Test
    fun `complex request is planned executed validated and stored in explicit layers`() {
        val model = RecordingModel(
            "1. Изучить контекст\n2. Выполнить задачу",
            "Готово",
            "VALID",
        )
        val agent = testAgent(model, PlanningPolicy { true })
        val session = agent.createSession()
        agent.remember(session.id, "working", "goal", "Сделать CLI")
        agent.remember(session.id, "long_term", "decision", "Используем Kotlin")
        val observedStates = mutableListOf<String>()

        agent.reply(session.id, "Продолжай") { observedStates += it.stage }

        assertEquals(3, model.calls.size)
        assertTrue(model.calls[0].taskState().contains("stage: planning"))
        assertTrue(model.calls[1].taskState().contains("stage: execution"))
        assertTrue(model.calls[1].workingMemory().contains("agent_plan"))
        assertTrue(model.calls[1].workingMemory().contains("Изучить контекст"))
        assertTrue(model.calls[2].taskState().contains("stage: validation"))
        assertTrue(model.calls[1][1].content.contains("Используем Kotlin"))

        val snapshot = agent.snapshot(session.id)
        assertEquals(listOf("user", "assistant"), snapshot.session.messages.map { it.role })
        assertEquals(listOf("goal", "agent_plan"), snapshot.working.map { it.category })
        assertEquals(1, snapshot.longTerm.size)
        assertEquals("done", snapshot.taskState.stage)
        assertTrue(snapshot.taskState.planningApplied)
        assertEquals(
            listOf("planning", "execution", "validation", "done"),
            snapshot.session.messages.last().stateTrace.map { it.stage },
        )
        assertEquals(snapshot.session.messages.last().stateTrace.map { it.stage }, observedStates)
    }

    @Test
    fun `simple request skips planning`() {
        val model = RecordingModel("Короткий ответ", "VALID")
        val agent = testAgent(model, PlanningPolicy { false })
        val session = agent.createSession()

        val response = agent.reply(session.id, "Привет")

        assertEquals("Короткий ответ", response.answer)
        assertEquals(2, model.calls.size)
        assertEquals(false, response.snapshot.taskState.planningApplied)
        assertTrue(response.snapshot.working.none { it.category == "agent_plan" })
        assertEquals(
            listOf("execution", "validation", "done"),
            response.snapshot.session.messages.last().stateTrace.map { it.stage },
        )
    }

    @Test
    fun `failed validation returns to execution and checks revised answer`() {
        val model = RecordingModel(
            "Первый черновик",
            "REVISE\nДобавить важную деталь",
            "Исправленный ответ",
            "VALID",
        )
        val agent = testAgent(model, PlanningPolicy { false })
        val session = agent.createSession()

        val response = agent.reply(session.id, "Объясни подробно")

        assertEquals("Исправленный ответ", response.answer)
        assertEquals(4, model.calls.size)
        assertEquals(
            listOf("execution", "validation", "execution", "validation", "done"),
            response.snapshot.session.messages.last().stateTrace.map { it.stage },
        )
        assertTrue(model.calls[2].last().content.contains("Добавить важную деталь"))
    }

    @Test
    fun `repeated validation rejection blocks until user clarification`() {
        val model = RecordingModel(
            "Неподходящий ответ",
            "REVISE\nЗапрос непонятен",
            "Другой неподходящий ответ",
            "REVISE\nНужно запросить уточнение",
        )
        val agent = testAgent(model, PlanningPolicy { false })
        val session = agent.createSession()

        val response = agent.reply(session.id, ".")

        assertEquals(
            "Проверка дважды нашла проблемы. Уточните требования, чтобы я продолжил эту задачу.",
            response.answer,
        )
        assertEquals("blocked", response.snapshot.taskState.stage)
        assertEquals("Ожидается уточнение пользователя", response.snapshot.taskState.expectedAction)
        assertEquals(2, response.snapshot.taskState.validationAttempts)
        assertEquals(
            listOf("execution", "validation", "execution", "validation", "blocked"),
            response.snapshot.session.messages.last().stateTrace.map { it.stage },
        )

        model.addAnswers("Ответ после уточнения", "VALID")
        val resumed = agent.reply(session.id, "Нужен только краткий итог")
        assertEquals("Ответ после уточнения", resumed.answer)
        assertEquals("done", resumed.snapshot.taskState.stage)
        assertEquals(
            listOf("execution", "validation", "done"),
            resumed.snapshot.session.messages.last().stateTrace.map { it.stage },
        )
    }

    @Test
    fun `failed model call does not add an unfinished turn`() {
        val model = object : LanguageModel {
            override fun complete(messages: List<PromptMessage>): String = throw AgentException("offline")
        }
        val agent = testAgent(model, PlanningPolicy { false })
        val session = agent.createSession()

        assertFailsWith<AgentException> { agent.reply(session.id, "Привет") }
        assertTrue(agent.snapshot(session.id).session.messages.isEmpty())
        val state = agent.snapshot(session.id).taskState
        assertEquals("failed", state.stage)
        assertEquals("offline", state.lastFailure)
    }

    private fun testAgent(model: LanguageModel, policy: PlanningPolicy): MemoryAgent {
        val store = FileMemoryStore(Files.createTempDirectory("memory-agent-test"))
        return MemoryAgent(store, model, "system", "test-model", policy)
    }

    private fun List<PromptMessage>.taskState(): String =
        first { it.content.startsWith("[TASK_STATE]") }.content

    private fun List<PromptMessage>.workingMemory(): String =
        first { it.content.startsWith("[WORKING_MEMORY]") }.content
}

private class RecordingModel(vararg answers: String) : LanguageModel {
    private val answers = ArrayDeque(answers.toList())
    val calls = mutableListOf<List<PromptMessage>>()

    override fun complete(messages: List<PromptMessage>): String {
        calls += messages
        return answers.removeFirstOrNull() ?: error("Для вызова модели не подготовлен ответ.")
    }

    fun addAnswers(vararg values: String) {
        answers.addAll(values.toList())
    }
}
