package memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InvariantGuardTest {
    private val invariants = listOf(
        InvariantItem("one", "architecture", "Использовать MVVM", "now"),
        InvariantItem("two", "stack_constraint", "Не использовать Python", "now"),
    )

    @Test
    fun `accepts only report that checks every invariant exactly once`() {
        val model = QueueModel(
            """{"verdict":"compliant","checks":[{"invariantId":"one","status":"satisfied","explanation":"MVVM"},{"invariantId":"two","status":"not_applicable","explanation":"Стек не меняется"}],"summary":"ok"}"""
        )

        val report = LlmInvariantGuard(model).evaluate("query", "draft", invariants)

        assertEquals(ComplianceReport.COMPLIANT, report.status)
        assertEquals(setOf("one", "two"), report.checks.map { it.invariantId }.toSet())
    }

    @Test
    fun `malformed or incomplete verifier response fails closed`() {
        val model = QueueModel(
            """{"verdict":"compliant","checks":[{"invariantId":"one","status":"satisfied","explanation":"ok"}],"summary":"ok"}"""
        )

        val report = LlmInvariantGuard(model).evaluate("query", "draft", invariants)

        assertEquals(ComplianceReport.UNVERIFIABLE, report.status)
        assertFalse(report.allowsResponse)
        assertEquals(2, report.checks.size)
    }
}

private class QueueModel(private val answer: String) : LanguageModel {
    override fun complete(messages: List<PromptMessage>): String = answer
}
