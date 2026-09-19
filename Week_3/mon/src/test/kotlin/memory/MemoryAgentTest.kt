package memory

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryAgentTest {
    @Test
    fun `prompt contains explicit memory layers and ordinary chat updates only short term`() {
        val model = RecordingModel("Готово")
        val agent = testAgent(model)
        val session = agent.createSession()
        agent.remember(session.id, "working", "goal", "Сделать CLI")
        agent.remember(session.id, "long_term", "decision", "Используем Kotlin")

        agent.reply(session.id, "Продолжай")

        val prompt = model.calls.single()
        assertEquals("system", prompt[0].role)
        assertTrue(prompt[1].content.contains("[LONG_TERM_MEMORY]"))
        assertTrue(prompt[1].content.contains("Используем Kotlin"))
        assertTrue(prompt[2].content.contains("[WORKING_MEMORY]"))
        assertTrue(prompt[2].content.contains("Сделать CLI"))
        assertEquals(PromptMessage("user", "Продолжай"), prompt.last())
        val snapshot = agent.snapshot(session.id)
        assertEquals(listOf("user", "assistant"), snapshot.session.messages.map { it.role })
        assertEquals(1, snapshot.working.size)
        assertEquals(1, snapshot.longTerm.size)
    }

    @Test
    fun `failed model call does not add an unfinished turn`() {
        val model = object : LanguageModel {
            override fun complete(messages: List<PromptMessage>): String = throw AgentException("offline")
        }
        val agent = testAgent(model)
        val session = agent.createSession()

        assertFailsWith<AgentException> { agent.reply(session.id, "Привет") }
        assertTrue(agent.snapshot(session.id).session.messages.isEmpty())
    }

    private fun testAgent(model: LanguageModel): MemoryAgent {
        val store = FileMemoryStore(Files.createTempDirectory("memory-agent-test"))
        return MemoryAgent(store, model, "system", "test-model")
    }
}

private class RecordingModel(private val answer: String) : LanguageModel {
    val calls = mutableListOf<List<PromptMessage>>()

    override fun complete(messages: List<PromptMessage>): String {
        calls += messages
        return answer
    }
}
