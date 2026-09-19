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
 * Память и обязательные инварианты физически разделены:
 *   short-term/<session>.json — диалог;
 *   working/<session>.json    — данные текущей задачи;
 *   long-term/memories.json   — знания между диалогами;
 *   invariants/invariants.json — глобальные правила, не являющиеся памятью.
 */
class FileMemoryStore(
    private val root: Path,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
) {
    private val shortTermDirectory = root.resolve("short-term")
    private val workingDirectory = root.resolve("working")
    private val longTermDirectory = root.resolve("long-term")
    private val invariantsDirectory = root.resolve("invariants")
    private val longTermFile = longTermDirectory.resolve("memories.json")
    private val invariantsFile = invariantsDirectory.resolve("invariants.json")

    init {
        shortTermDirectory.createDirectories()
        workingDirectory.createDirectories()
        longTermDirectory.createDirectories()
        invariantsDirectory.createDirectories()
        if (!longTermFile.exists()) writeAtomic(longTermFile, MemoryDocument())
        if (!invariantsFile.exists()) writeAtomic(invariantsFile, InvariantDocument())
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
            invariants = readInvariants(),
        )
    }

    @Synchronized
    fun appendTurn(
        sessionId: String,
        userText: String,
        answer: String,
        compliance: ComplianceReport,
    ) {
        val session = requireSession(sessionId)
        val now = Instant.now().toString()
        val messages = session.messages.orEmpty() + listOf(
            ChatMessage(UUID.randomUUID().toString(), "user", userText, now),
            ChatMessage(UUID.randomUUID().toString(), "assistant", answer, now, compliance = compliance),
        )
        val title = if (session.messages.orEmpty().isEmpty()) titleFrom(userText) else session.title
        writeAtomic(shortTermFile(sessionId), session.copy(title = title, updatedAt = now, messages = messages))
    }

    @Synchronized
    fun remember(sessionId: String, layer: MemoryLayer, category: String, content: String): MemoryItem {
        val session = requireSession(sessionId)
        val item = MemoryItem(
            id = UUID.randomUUID().toString(),
            category = MemoryCategories.validate(layer, category),
            content = validateContent(content, "Запись памяти"),
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
                    session.copy(updatedAt = item.createdAt, messages = session.messages.orEmpty() + note),
                )
            }
            MemoryLayer.WORKING -> appendMemory(workingFile(sessionId), item)
            MemoryLayer.LONG_TERM -> appendMemory(longTermFile, item)
        }
        return item
    }

    @Synchronized
    fun addInvariant(category: String, content: String): InvariantItem {
        val item = InvariantItem(
            id = UUID.randomUUID().toString(),
            category = InvariantCategories.validate(category),
            content = validateContent(content, "Инвариант"),
            createdAt = Instant.now().toString(),
        )
        writeAtomic(invariantsFile, InvariantDocument(readInvariants() + item))
        return item
    }

    @Synchronized
    fun removeInvariant(invariantId: String) {
        val current = readInvariants()
        val updated = current.filterNot { it.id == invariantId }
        require(updated.size != current.size) { "Инвариант '$invariantId' не найден." }
        writeAtomic(invariantsFile, InvariantDocument(updated))
    }

    @Synchronized
    fun forget(sessionId: String, layer: MemoryLayer, memoryId: String) {
        val session = requireSession(sessionId)
        val removed = when (layer) {
            MemoryLayer.SHORT_TERM -> {
                val messages = session.messages.orEmpty()
                val updated = messages.filterNot { it.id == memoryId && it.role == "memory" }
                if (updated.size == messages.size) false else {
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
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            "Некорректный идентификатор диалога."
        }
        val path = shortTermFile(id)
        require(path.exists()) { "Диалог '$id' не найден." }
        return readSession(path)
    }

    private fun shortTermFile(id: String): Path = shortTermDirectory.resolve("$id.json")
    private fun workingFile(id: String): Path = workingDirectory.resolve("$id.json")

    private fun readSession(path: Path): SessionDocument = gson.fromJson(path.readText(), SessionDocument::class.java)

    private fun readMemories(path: Path): List<MemoryItem> {
        if (!path.exists()) return emptyList()
        return gson.fromJson(path.readText(), MemoryDocument::class.java)?.items.orEmpty()
    }

    private fun readInvariants(): List<InvariantItem> =
        gson.fromJson(invariantsFile.readText(), InvariantDocument::class.java)?.items.orEmpty()

    private fun writeAtomic(path: Path, value: Any) {
        path.parent.createDirectories()
        val temporary = Files.createTempFile(path.parent, ".agent-", ".tmp")
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

    private fun validateContent(content: String, kind: String): String {
        val normalized = content.trim()
        require(normalized.isNotEmpty()) { "$kind не может быть пустым." }
        require(normalized.length <= 4_000) { "$kind не должен превышать 4000 символов." }
        return normalized
    }

    private fun titleFrom(text: String): String {
        val oneLine = text.trim().replace(Regex("\\s+"), " ")
        return if (oneLine.length <= 42) oneLine else oneLine.take(39) + "…"
    }

    private fun SessionDocument.summary() = SessionSummary(id, title, updatedAt, messages.orEmpty().size)
}
