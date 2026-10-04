package memorychat

import enhancedrag.RetrievalSettings
import groundedrag.GroundedAnswer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun interface GroundedAnswerer {
    suspend fun answer(question: String, settings: RetrievalSettings): GroundedAnswer
}

class MemoryChatService(
    private val memoryPlanner: TaskMemoryPlanner,
    private val answerer: GroundedAnswerer,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> String = { Instant.now().toString() },
) {
    private val sessions = ConcurrentHashMap<String, SessionRecord>()

    fun create(): ChatSnapshot {
        val timestamp = now()
        val record = SessionRecord(idFactory(), timestamp, timestamp)
        sessions[record.id] = record
        return record.snapshot()
    }

    suspend fun get(id: String): ChatSnapshot {
        val record = session(id)
        return record.mutex.withLock { record.snapshot() }
    }

    fun delete(id: String): Boolean = sessions.remove(id) != null

    suspend fun send(
        sessionId: String,
        rawMessage: String,
        settings: RetrievalSettings = RetrievalSettings(),
    ): ChatReply {
        val message = rawMessage.trim()
        require(message.isNotEmpty()) { "Введите сообщение." }
        require(message.length <= 1_000) { "Сообщение не должно превышать 1000 символов." }
        val retrieval = settings.validated()
        val record = session(sessionId)
        return record.mutex.withLock {
            val plan = memoryPlanner.update(record.memory, record.messages.toList(), message)
            val grounded = answerer.answer(
                contextualQuestion(message, plan, record.messages),
                retrieval,
            )
            val timestamp = now()
            val userMessage = StoredChatMessage(
                id = idFactory(),
                role = "user",
                content = message,
                createdAt = timestamp,
            )
            val assistantMessage = StoredChatMessage(
                id = idFactory(),
                role = "assistant",
                content = grounded.answer,
                sources = grounded.sources,
                quotes = grounded.quotes,
                needsClarification = grounded.needsClarification,
                createdAt = timestamp,
            )
            record.memory = plan.memory
            record.messages += userMessage
            record.messages += assistantMessage
            record.updatedAt = timestamp
            ChatReply(record.snapshot(), assistantMessage)
        }
    }

    private fun contextualQuestion(
        currentMessage: String,
        plan: MemoryPlan,
        history: List<StoredChatMessage>,
    ): String = buildString {
        appendLine("<search_query>${plan.standaloneQuestion.take(700)}</search_query>")
        appendLine("Текущая реплика пользователя: ${currentMessage.take(700)}")
        appendLine("Память задачи:")
        plan.memory.goal?.let { appendLine("Цель: ${it.take(400)}") }
        if (plan.memory.clarifiedFacts.isNotEmpty()) {
            appendLine("Уточнено: ${plan.memory.clarifiedFacts.joinToString("; ").take(450)}")
        }
        if (plan.memory.constraints.isNotEmpty()) {
            appendLine("Ограничения: ${plan.memory.constraints.joinToString("; ").take(450)}")
        }
        if (plan.memory.terms.isNotEmpty()) {
            appendLine("Термины: ${plan.memory.terms.joinToString("; ") { "${it.term} — ${it.meaning}" }.take(450)}")
        }
        val recent = history.takeLast(4).joinToString("\n") {
            "${if (it.role == "user") "Пользователь" else "Ассистент"}: ${it.content.take(180)}"
        }
        if (recent.isNotBlank()) appendLine("Недавний диалог:\n$recent")
        append("Ответь на текущую реплику, сохраняя цель и ограничения диалога. Используй только найденные источники.")
    }.take(MAX_GROUNDED_QUESTION_LENGTH)

    private fun session(id: String): SessionRecord = sessions[id]
        ?: throw NoSuchElementException("Чат '$id' не найден.")

    private class SessionRecord(
        val id: String,
        val createdAt: String,
        var updatedAt: String,
        val mutex: Mutex = Mutex(),
        var memory: TaskMemory = TaskMemory(),
        val messages: MutableList<StoredChatMessage> = mutableListOf(),
    ) {
        fun snapshot() = ChatSnapshot(id, messages.toList(), memory, createdAt, updatedAt)
    }

    private companion object {
        const val MAX_GROUNDED_QUESTION_LENGTH = 1_950
    }
}
