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
        assertTrue(prompt[1].content.contains("[USER_PROFILE]"))
        assertTrue(prompt[1].content.contains("Нейтральный профиль"))
        assertTrue(prompt[1].content.contains("preferred_style:"))
        assertTrue(prompt[1].content.contains("preferred_format:"))
        assertTrue(prompt[1].content.contains("constraints:"))
        assertTrue(prompt[2].content.contains("[LONG_TERM_MEMORY]"))
        assertTrue(prompt[2].content.contains("Используем Kotlin"))
        assertTrue(prompt[3].content.contains("[WORKING_MEMORY]"))
        assertTrue(prompt[3].content.contains("Сделать CLI"))
        assertEquals(PromptMessage("user", "Продолжай"), prompt.last())
        val snapshot = agent.snapshot(session.id)
        assertEquals(listOf("user", "assistant"), snapshot.session.messages.map { it.role })
        assertEquals(1, snapshot.working.size)
        assertEquals(1, snapshot.longTerm.size)
    }

    @Test
    fun `same request produces different automatic personalization for different profiles`() {
        val store = FileMemoryStore(Files.createTempDirectory("profile-answer-test"))
        val agent = MemoryAgent(store, DemoLanguageModel(), "system", "demo")
        val concise = agent.createProfile(ProfileRequest(
            name = "Краткий эксперт",
            style = "Кратко и строго",
            format = "Один абзац",
            constraints = listOf("Не использовать вступление"),
        ))
        val mentor = agent.createProfile(ProfileRequest(
            name = "Наставник",
            style = "Подробно и доброжелательно",
            format = "Пошаговый Markdown",
            constraints = listOf("Объяснять термины"),
        ))
        val conciseSession = agent.createSession(concise.id)
        val mentorSession = agent.createSession(mentor.id)

        val conciseAnswer = agent.reply(conciseSession.id, "Объясни память").answer
        val mentorAnswer = agent.reply(mentorSession.id, "Объясни память").answer

        assertTrue(conciseAnswer.contains("Краткий эксперт"))
        assertTrue(conciseAnswer.contains("Кратко и строго"))
        assertTrue(mentorAnswer.contains("Наставник"))
        assertTrue(mentorAnswer.contains("Пошаговый Markdown"))
        assertTrue(conciseAnswer != mentorAnswer)
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
