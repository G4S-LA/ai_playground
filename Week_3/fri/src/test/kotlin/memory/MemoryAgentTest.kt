package memory

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryAgentTest {
    @Test
    fun `new task stops at planning until explicit approval`() {
        val model = RecordingModel(
            "1. Изучить контекст\n2. Реализовать задачу\n3. Проверить результат",
            "Готовый результат",
            "VALID",
        )
        val agent = testAgent(model)
        val session = agent.createSession()
        agent.remember(session.id, "working", "goal", "Сделать CLI")

        val planned = agent.reply(session.id, "Реализуй CLI")

        assertEquals(1, model.calls.size)
        assertEquals("planning", planned.snapshot.taskState.stage)
        assertTrue(planned.snapshot.taskState.planReady)
        assertFalse(planned.snapshot.taskState.planApproved)
        assertEquals(
            listOf("planning", "planning"),
            planned.snapshot.session.messages.last().stateTrace.map { it.stage },
        )
        assertEquals(
            listOf("goal", "agent_task", "agent_plan"),
            planned.snapshot.working.map { it.category },
        )

        val completed = agent.approvePlan(session.id)

        assertEquals("Готовый результат", completed.answer)
        assertEquals(3, model.calls.size)
        assertEquals("done", completed.snapshot.taskState.stage)
        assertTrue(completed.snapshot.taskState.planApproved)
        assertEquals(
            listOf("execution", "validation", "done"),
            completed.snapshot.session.messages.last().stateTrace.map { it.stage },
        )
        assertEquals("План утверждён.", completed.snapshot.session.messages.takeLast(2).first().content)
    }

    @Test
    fun `feedback revises plan and still waits for approval`() {
        val model = RecordingModel(
            "1. Сделать API\n2. Проверить",
            "1. Сначала написать тесты\n2. Сделать API\n3. Проверить",
            "Результат с тестами",
            "VALID",
        )
        val agent = testAgent(model)
        val session = agent.createSession()
        agent.reply(session.id, "Реализуй API")

        val revised = agent.reply(session.id, "Добавь написание тестов первым шагом")

        assertEquals("planning", revised.snapshot.taskState.stage)
        assertTrue(revised.snapshot.taskState.planReady)
        assertFalse(revised.snapshot.taskState.planApproved)
        assertEquals(2, model.calls.size)
        assertTrue(model.calls[1].last().content.contains("Добавь написание тестов"))
        assertTrue(
            revised.snapshot.working.single { it.category == "agent_plan" }
                .content.contains("Сначала написать тесты")
        )

        assertEquals("Результат с тестами", agent.approvePlan(session.id).answer)
    }

    @Test
    fun `failed validation returns to execution after plan approval`() {
        val model = RecordingModel(
            "1. Подготовить ответ\n2. Проверить полноту",
            "Первый результат",
            "REVISE\nДобавить важную деталь",
            "Исправленный результат",
            "VALID",
        )
        val agent = testAgent(model)
        val session = agent.createSession()
        agent.reply(session.id, "Объясни подробно")

        val response = agent.approvePlan(session.id)

        assertEquals("Исправленный результат", response.answer)
        assertEquals(5, model.calls.size)
        assertEquals(
            listOf("execution", "validation", "execution", "validation", "done"),
            response.snapshot.session.messages.last().stateTrace.map { it.stage },
        )
        assertTrue(model.calls[3].last().content.contains("Добавить важную деталь"))
    }

    @Test
    fun `repeated validation rejection blocks until user clarification`() {
        val model = RecordingModel(
            "1. Уточнить смысл\n2. Подготовить результат",
            "Неподходящий результат",
            "REVISE\nЗапрос непонятен",
            "Другой неподходящий результат",
            "REVISE\nНужно уточнение",
        )
        val agent = testAgent(model)
        val session = agent.createSession()
        agent.reply(session.id, "Подготовь результат")

        val blocked = agent.approvePlan(session.id)

        assertEquals("blocked", blocked.snapshot.taskState.stage)
        assertEquals(2, blocked.snapshot.taskState.validationAttempts)
        assertEquals(
            listOf("execution", "validation", "execution", "validation", "blocked"),
            blocked.snapshot.session.messages.last().stateTrace.map { it.stage },
        )

        model.addAnswers("Результат после уточнения", "VALID")
        val resumed = agent.reply(session.id, "Нужен только краткий итог")
        assertEquals("Результат после уточнения", resumed.answer)
        assertEquals("done", resumed.snapshot.taskState.stage)
        assertEquals(
            listOf("execution", "validation", "done"),
            resumed.snapshot.session.messages.last().stateTrace.map { it.stage },
        )
    }

    @Test
    fun `plan cannot be approved before it exists`() {
        val agent = testAgent(RecordingModel())
        val session = agent.createSession()

        assertFailsWith<LifecycleException> { agent.approvePlan(session.id) }
        assertEquals("idle", agent.snapshot(session.id).taskState.stage)
    }

    @Test
    fun `failed planning call does not add an unfinished turn`() {
        val model = object : LanguageModel {
            override fun complete(messages: List<PromptMessage>): String = throw AgentException("offline")
        }
        val agent = testAgent(model)
        val session = agent.createSession()

        assertFailsWith<AgentException> { agent.reply(session.id, "Привет") }
        assertTrue(agent.snapshot(session.id).session.messages.isEmpty())
        val state = agent.snapshot(session.id).taskState
        assertEquals("failed", state.stage)
        assertEquals("offline", state.lastFailure)
    }

    private fun testAgent(model: LanguageModel): MemoryAgent {
        val store = FileMemoryStore(Files.createTempDirectory("memory-agent-test"))
        return MemoryAgent(store, model, "system", "test-model")
    }
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
