package memorychat

import enhancedrag.RetrievalSettings
import groundedrag.AbstentionReason
import groundedrag.AnswerValidation
import groundedrag.GroundedAnswer
import groundedrag.GroundedSource
import groundedrag.VerifiedQuote
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryChatServiceTest {
    @Test
    fun `keeps goal and sources through twelve message scenario`() = runBlocking {
        val capturedQuestions = mutableListOf<String>()
        val service = service(
            planner = TaskMemoryPlanner { previous, _, current ->
                MemoryPlan(
                    previous.copy(
                        goal = previous.goal ?: "Разобраться в устройстве RAG",
                        clarifiedFacts = (previous.clarifiedFacts + current).takeLast(12),
                        revision = previous.revision + 1,
                    ),
                    current,
                )
            },
            capturedQuestions = capturedQuestions,
        )
        val chat = service.create()

        repeat(12) { index -> service.send(chat.id, "Уточнение ${index + 1}") }
        val snapshot = service.get(chat.id)

        assertEquals(24, snapshot.messages.size)
        assertEquals("Разобраться в устройстве RAG", snapshot.memory.goal)
        assertTrue(snapshot.messages.filter { it.role == "assistant" }.all { it.sources.isNotEmpty() })
        assertTrue("Разобраться в устройстве RAG" in capturedQuestions.last())
    }

    @Test
    fun `keeps constraints and terms through fifteen message scenario`() = runBlocking {
        val capturedQuestions = mutableListOf<String>()
        val service = service(
            planner = TaskMemoryPlanner { previous, _, current ->
                MemoryPlan(
                    previous.copy(
                        goal = previous.goal ?: "Выбрать архитектуру памяти агента",
                        constraints = listOf("Только локальные embeddings", "Ответы с источниками"),
                        terms = listOf(MemoryTerm("память", "состояние текущей задачи")),
                        revision = previous.revision + 1,
                    ),
                    current,
                )
            },
            capturedQuestions = capturedQuestions,
        )
        val chat = service.create()

        repeat(15) { index -> service.send(chat.id, "Сообщение ${index + 1}") }
        val snapshot = service.get(chat.id)

        assertEquals(30, snapshot.messages.size)
        assertEquals(15, snapshot.memory.revision)
        assertEquals(2, snapshot.memory.constraints.size)
        assertEquals("состояние текущей задачи", snapshot.memory.terms.single().meaning)
        assertTrue("Только локальные embeddings" in capturedQuestions.last())
        assertTrue(snapshot.messages.filter { it.role == "assistant" }.all { it.sources.single().chunkId == "chunk-1" })
    }

    @Test
    fun `stores validation failure diagnostics instead of unverified source`() = runBlocking {
        var id = 0
        val service = MemoryChatService(
            memoryPlanner = TaskMemoryPlanner { previous, _, current ->
                MemoryPlan(previous.copy(goal = "Проверить ответ", revision = previous.revision + 1), current)
            },
            answerer = GroundedAnswerer { question, settings ->
                groundedAnswer(question, settings).copy(
                    answer = "Не знаю: ответ не прошёл проверку.",
                    sources = emptyList(),
                    quotes = emptyList(),
                    needsClarification = true,
                    validation = AnswerValidation(false, listOf("Цитата отсутствует в чанке."), 2, false),
                    abstentionReason = AbstentionReason.VALIDATION_FAILED,
                )
            },
            idFactory = { "id-${++id}" },
            now = { "2026-10-04T12:00:00Z" },
        )

        val chat = service.create()
        val reply = service.send(chat.id, "Что известно?")

        assertEquals("validation_failed", reply.message.abstentionReason)
        assertEquals("RAG", reply.message.searchQuery)
        assertEquals(2, reply.message.generationAttempts)
        assertEquals(listOf("Цитата отсутствует в чанке."), reply.message.validationErrors)
        assertTrue(reply.message.sources.isEmpty())
    }

    private fun service(
        planner: TaskMemoryPlanner,
        capturedQuestions: MutableList<String>,
    ): MemoryChatService {
        var id = 0
        return MemoryChatService(
            memoryPlanner = planner,
            answerer = GroundedAnswerer { question, settings ->
                capturedQuestions += question
                groundedAnswer(question, settings)
            },
            idFactory = { "id-${++id}" },
            now = { "2026-10-03T12:00:00Z" },
        )
    }

    private fun groundedAnswer(question: String, settings: RetrievalSettings) = GroundedAnswer(
        question = question,
        searchQuery = "RAG",
        answer = "Подтверждённый ответ [S1].",
        sources = listOf(GroundedSource("S1", "book.md", "RAG", "chunk-1", 0.9, 0.9)),
        quotes = listOf(VerifiedQuote("S1", "Дословная цитата из источника.")),
        candidates = emptyList(),
        settings = settings,
        needsClarification = false,
        clarificationPrompt = null,
        validation = AnswerValidation(true, emptyList(), 1, false),
        rewriteMs = 0,
        elapsedMs = 1,
    )
}
