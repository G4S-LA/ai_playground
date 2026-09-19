package memory

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Физически разделяет слои памяти:
 *   short-term/<session>.json  — текущий диалог;
 *   working/<session>.json     — данные текущей задачи;
 *   long-term/memories.json    — общий профиль, решения и знания.
 *   task-state/<session>.json   — формальное состояние текущей задачи.
 */
class FileMemoryStore(
    private val root: Path,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
) {
    private val shortTermDirectory = root.resolve("short-term")
    private val workingDirectory = root.resolve("working")
    private val longTermDirectory = root.resolve("long-term")
    private val taskStateDirectory = root.resolve("task-state")
    private val longTermFile = longTermDirectory.resolve("memories.json")
    private val stateMachine = TaskStateMachine()

    init {
        shortTermDirectory.createDirectories()
        workingDirectory.createDirectories()
        longTermDirectory.createDirectories()
        taskStateDirectory.createDirectories()
        if (!longTermFile.exists()) writeAtomic(longTermFile, MemoryDocument())
    }

    @Synchronized
    fun createSession(): SessionSummary {
        val now = Instant.now().toString()
        val session = SessionDocument(
            id = UUID.randomUUID().toString(),
            title = "Новый диалог",
            createdAt = now,
            updatedAt = now,
        )
        writeAtomic(shortTermFile(session.id), session)
        writeAtomic(workingFile(session.id), MemoryDocument())
        writeAtomic(taskStateFile(session.id), TaskState.initial(now))
        return session.summary()
    }

    @Synchronized
    fun listSessions(): List<SessionSummary> = shortTermDirectory.listDirectoryEntries("*.json")
        .mapNotNull { path -> runCatching { readSession(path).summary() }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    @Synchronized
    fun snapshot(sessionId: String): MemorySnapshot {
        val session = requireSession(sessionId)
        return MemorySnapshot(
            session = session,
            working = readMemories(workingFile(sessionId)),
            longTerm = readMemories(longTermFile),
            taskState = readTaskState(sessionId),
        )
    }

    @Synchronized
    fun replaceWorkingPlan(sessionId: String, content: String): MemoryItem {
        requireSession(sessionId)
        val item = MemoryItem(
            id = UUID.randomUUID().toString(),
            category = "agent_plan",
            content = validateContent(content),
            createdAt = Instant.now().toString(),
        )
        val withoutOldPlan = readMemories(workingFile(sessionId)).filterNot { it.category == "agent_plan" }
        writeAtomic(workingFile(sessionId), MemoryDocument(withoutOldPlan + item))
        return item
    }

    @Synchronized
    fun clearWorkingPlan(sessionId: String) {
        requireSession(sessionId)
        val current = readMemories(workingFile(sessionId))
        val withoutPlan = current.filterNot { it.category == "agent_plan" }
        if (withoutPlan.size != current.size) {
            writeAtomic(workingFile(sessionId), MemoryDocument(withoutPlan))
        }
    }

    @Synchronized
    fun applyTaskEvent(
        sessionId: String,
        event: TaskEvent,
        expectedAction: String,
        failureReason: String? = null,
    ): TransitionResult {
        requireSession(sessionId)
        val result = stateMachine.dispatch(
            readTaskState(sessionId),
            event,
            expectedAction,
            failureReason,
        )
        if (result is TransitionResult.Accepted) {
            writeAtomic(taskStateFile(sessionId), result.state)
        }
        return result
    }

    @Synchronized
    fun appendTurn(
        sessionId: String,
        userText: String,
        answer: String,
        stateTrace: List<TaskState> = emptyList(),
    ) {
        val session = requireSession(sessionId)
        val now = Instant.now().toString()
        val messages = session.messages + listOf(
            ChatMessage(UUID.randomUUID().toString(), "user", userText, now),
            ChatMessage(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = answer,
                createdAt = now,
                stateTrace = stateTrace,
            ),
        )
        val title = if (session.messages.isEmpty()) titleFrom(userText) else session.title
        writeAtomic(
            shortTermFile(sessionId),
            session.copy(title = title, updatedAt = now, messages = messages),
        )
    }

    @Synchronized
    fun remember(sessionId: String, layer: MemoryLayer, category: String, content: String): MemoryItem {
        val session = requireSession(sessionId)
        val normalizedCategory = MemoryCategories.validate(layer, category)
        val normalizedContent = validateContent(content)
        val item = MemoryItem(
            id = UUID.randomUUID().toString(),
            category = normalizedCategory,
            content = normalizedContent,
            createdAt = Instant.now().toString(),
        )
        when (layer) {
            MemoryLayer.SHORT_TERM -> {
                val note = ChatMessage(
                    id = item.id,
                    role = "memory",
                    content = item.content,
                    createdAt = item.createdAt,
                    category = item.category,
                )
                writeAtomic(
                    shortTermFile(sessionId),
                    session.copy(updatedAt = item.createdAt, messages = session.messages + note),
                )
            }
            MemoryLayer.WORKING -> appendMemory(workingFile(sessionId), item)
            MemoryLayer.LONG_TERM -> appendMemory(longTermFile, item)
        }
        return item
    }

    @Synchronized
    fun forget(sessionId: String, layer: MemoryLayer, memoryId: String) {
        val session = requireSession(sessionId)
        val removed = when (layer) {
            MemoryLayer.SHORT_TERM -> {
                val updated = session.messages.filterNot { it.id == memoryId && it.role == "memory" }
                if (updated.size == session.messages.size) false else {
                    writeAtomic(
                        shortTermFile(sessionId),
                        session.copy(updatedAt = Instant.now().toString(), messages = updated),
                    )
                    true
                }
            }
            MemoryLayer.WORKING -> removeMemory(workingFile(sessionId), memoryId)
            MemoryLayer.LONG_TERM -> removeMemory(longTermFile, memoryId)
        }
        require(removed) { "Запись памяти '$memoryId' не найдена в ${layer.wireName}." }
    }

    @Synchronized
    fun deleteSession(sessionId: String) {
        requireSession(sessionId)
        Files.delete(shortTermFile(sessionId))
        Files.deleteIfExists(workingFile(sessionId))
        Files.deleteIfExists(taskStateFile(sessionId))
    }

    private fun appendMemory(path: Path, item: MemoryItem) {
        writeAtomic(path, MemoryDocument(readMemories(path) + item))
    }

    private fun removeMemory(path: Path, id: String): Boolean {
        val current = readMemories(path)
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return false
        writeAtomic(path, MemoryDocument(updated))
        return true
    }

    private fun requireSession(id: String): SessionDocument {
        require(UUID.fromString(id).toString() == id) { "Некорректный идентификатор диалога." }
        val path = shortTermFile(id)
        require(path.exists()) { "Диалог '$id' не найден." }
        return readSession(path)
    }

    private fun shortTermFile(id: String): Path = shortTermDirectory.resolve("$id.json")
    private fun workingFile(id: String): Path = workingDirectory.resolve("$id.json")
    private fun taskStateFile(id: String): Path = taskStateDirectory.resolve("$id.json")

    private fun readSession(path: Path): SessionDocument = gson.fromJson(path.readText(), SessionDocument::class.java)

    private fun readMemories(path: Path): List<MemoryItem> {
        if (!path.exists()) return emptyList()
        return gson.fromJson(path.readText(), MemoryDocument::class.java)?.items.orEmpty()
    }

    private fun readTaskState(sessionId: String): TaskState {
        val path = taskStateFile(sessionId)
        if (!path.exists()) {
            val initial = TaskState.initial()
            writeAtomic(path, initial)
            return initial
        }
        return gson.fromJson(path.readText(), TaskState::class.java)
    }

    private fun writeAtomic(path: Path, value: Any) {
        path.parent.createDirectories()
        val temporary = Files.createTempFile(path.parent, ".memory-", ".tmp")
        try {
            temporary.writeText(gson.toJson(value))
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun validateContent(content: String): String {
        val normalized = content.trim()
        require(normalized.isNotEmpty()) { "Нельзя сохранить пустую запись памяти." }
        require(normalized.length <= 4_000) { "Запись памяти не должна превышать 4000 символов." }
        return normalized
    }

    private fun titleFrom(text: String): String {
        val oneLine = text.trim().replace(Regex("\\s+"), " ")
        return if (oneLine.length <= 42) oneLine else oneLine.take(39) + "…"
    }

    private fun SessionDocument.summary() = SessionSummary(id, title, updatedAt, messages.size)
}
