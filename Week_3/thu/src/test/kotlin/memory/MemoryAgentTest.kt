package memory

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryAgentTest {
    @Test
    fun `prompt gives invariants priority and guard explicitly checks them`() {
        val model = RecordingModel("Используем Kotlin и MVVM")
        val guard = RecordingGuard()
        val agent = testAgent(model, guard)
        val session = agent.createSession()
        val invariant = agent.addInvariant(session.id, "architecture", "Архитектура только MVVM").invariant
        agent.remember(session.id, "working", "constraint", "Можно рассмотреть MVC")

        val response = agent.reply(session.id, "Предложи архитектуру")

        val prompt = model.calls.single()
        assertTrue(prompt[1].content.startsWith("[NON_NEGOTIABLE_INVARIANTS]"))
        assertTrue(prompt[1].content.contains(invariant.id))
        assertTrue(prompt[3].content.startsWith("[WORKING_MEMORY]"))
        assertEquals(listOf(invariant.id), guard.seen.single().map { it.id })
        assertEquals(ComplianceReport.COMPLIANT, response.compliance.status)
        assertEquals("Используем Kotlin и MVVM", response.answer)
    }

    @Test
    fun `violating draft is never returned and is replaced by refusal`() {
        val draft = "Давайте перепишем сервис на Python"
        val model = RecordingModel(draft)
        val guard = FixedGuard(ComplianceReport(
            status = ComplianceReport.VIOLATION,
            checks = listOf(InvariantCheck("will-be-replaced", "violated", "Python запрещён стеком.")),
            summary = "Есть конфликт.",
        ))
        val store = FileMemoryStore(Files.createTempDirectory("invariant-agent-test"))
        val session = store.createSession()
        val invariant = store.addInvariant("stack_constraint", "Запрещено использовать Python")
        guard.report = guard.report.copy(
            checks = listOf(InvariantCheck(invariant.id, "violated", "Python запрещён стеком.")),
        )
        val agent = MemoryAgent(store, model, guard, "system", "test-model")

        val response = agent.reply(session.id, "Предложи Python")

        assertFalse(response.answer.contains(draft))
        assertTrue(response.answer.startsWith("Не могу предложить это решение"))
        assertTrue(response.answer.contains("Запрещено использовать Python"))
        assertEquals(ComplianceReport.VIOLATION, response.compliance.status)
        val saved = agent.snapshot(session.id).session.messages.last()
        assertEquals(response.answer, saved.content)
        assertEquals(ComplianceReport.VIOLATION, saved.compliance?.status)
    }

    @Test
    fun `unverifiable guard fails closed`() {
        val model = RecordingModel("Непроверенный черновик")
        val guard = FixedGuard(ComplianceReport(
            status = ComplianceReport.UNVERIFIABLE,
            checks = emptyList(),
            summary = "Ошибка проверки",
        ))
        val agent = testAgent(model, guard)
        val session = agent.createSession()
        agent.addInvariant(session.id, "business_rule", "Не удалять пользовательские данные")

        val response = agent.reply(session.id, "Что делать?")

        assertFalse(response.answer.contains("Непроверенный черновик"))
        assertTrue(response.answer.contains("проверка соблюдения инвариантов"))
    }

    private fun testAgent(model: LanguageModel, guard: InvariantGuard): MemoryAgent = MemoryAgent(
        FileMemoryStore(Files.createTempDirectory("invariant-agent-test")),
        model,
        guard,
        "system",
        "test-model",
    )
}

private class RecordingModel(private val answer: String) : LanguageModel {
    val calls = mutableListOf<List<PromptMessage>>()
    override fun complete(messages: List<PromptMessage>): String {
        calls += messages
        return answer
    }
}

private class RecordingGuard : InvariantGuard {
    val seen = mutableListOf<List<InvariantItem>>()
    override fun evaluate(query: String, draft: String, invariants: List<InvariantItem>): ComplianceReport {
        seen += invariants
        return ComplianceReport(
            ComplianceReport.COMPLIANT,
            invariants.map { InvariantCheck(it.id, "satisfied", "Соблюдён") },
            "Все правила соблюдены.",
        )
    }
}

private class FixedGuard(var report: ComplianceReport) : InvariantGuard {
    override fun evaluate(query: String, draft: String, invariants: List<InvariantItem>): ComplianceReport = report
}
