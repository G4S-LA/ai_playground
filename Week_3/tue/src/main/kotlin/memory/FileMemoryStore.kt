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
 *   profiles/<profile>.json    — предпочтения конкретного пользователя.
 */
class FileMemoryStore(
    private val root: Path,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
) {
    private val shortTermDirectory = root.resolve("short-term")
    private val workingDirectory = root.resolve("working")
    private val longTermDirectory = root.resolve("long-term")
    private val profilesDirectory = root.resolve("profiles")
    private val longTermFile = longTermDirectory.resolve("memories.json")

    init {
        shortTermDirectory.createDirectories()
        workingDirectory.createDirectories()
        longTermDirectory.createDirectories()
        profilesDirectory.createDirectories()
        if (!longTermFile.exists()) writeAtomic(longTermFile, MemoryDocument())
        if (!profileFile(UserProfiles.DEFAULT_ID).exists()) {
            val now = Instant.now().toString()
            writeAtomic(
                profileFile(UserProfiles.DEFAULT_ID),
                UserProfile(
                    id = UserProfiles.DEFAULT_ID,
                    name = "Нейтральный профиль",
                    style = "Дружелюбный, ясный и профессиональный",
                    format = "Краткие абзацы; списки использовать только когда они улучшают читаемость",
                    constraints = listOf(
                        "Не выдумывать факты",
                        "Явно отмечать существенную неопределённость",
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    @Synchronized
    fun createSession(profileId: String = UserProfiles.DEFAULT_ID): SessionSummary {
        val profile = requireProfile(profileId)
        val now = Instant.now().toString()
        val session = SessionDocument(
            id = UUID.randomUUID().toString(),
            title = "Новый диалог",
            createdAt = now,
            updatedAt = now,
            profileId = profile.id,
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
            profile = requireProfile(session.profileId ?: UserProfiles.DEFAULT_ID),
        )
    }

    @Synchronized
    fun listProfiles(): List<UserProfile> = profilesDirectory.listDirectoryEntries("*.json")
        .mapNotNull { path -> runCatching { readProfile(path) }.getOrNull() }
        .sortedWith(compareBy<UserProfile> { it.id != UserProfiles.DEFAULT_ID }.thenBy { it.name.lowercase() })

    @Synchronized
    fun createProfile(
        name: String,
        style: String,
        format: String,
        constraints: List<String?>,
    ): UserProfile {
        val now = Instant.now().toString()
        val profile = UserProfile(
            id = UUID.randomUUID().toString(),
            name = validateProfileField("Название", name, 80),
            style = validateProfileField("Стиль", style, 500),
            format = validateProfileField("Формат", format, 500),
            constraints = validateConstraints(constraints),
            createdAt = now,
            updatedAt = now,
        )
        writeAtomic(profileFile(profile.id), profile)
        return profile
    }

    @Synchronized
    fun updateProfile(
        profileId: String,
        name: String,
        style: String,
        format: String,
        constraints: List<String?>,
    ): UserProfile {
        val current = requireProfile(profileId)
        val updated = current.copy(
            name = validateProfileField("Название", name, 80),
            style = validateProfileField("Стиль", style, 500),
            format = validateProfileField("Формат", format, 500),
            constraints = validateConstraints(constraints),
            updatedAt = Instant.now().toString(),
        )
        writeAtomic(profileFile(current.id), updated)
        return updated
    }

    @Synchronized
    fun selectProfile(sessionId: String, profileId: String) {
        val session = requireSession(sessionId)
        val profile = requireProfile(profileId)
        writeAtomic(
            shortTermFile(sessionId),
            session.copy(profileId = profile.id, updatedAt = Instant.now().toString()),
        )
    }

    @Synchronized
    fun deleteProfile(profileId: String) {
        val profile = requireProfile(profileId)
        require(profile.id != UserProfiles.DEFAULT_ID) { "Нейтральный профиль удалить нельзя." }
        shortTermDirectory.listDirectoryEntries("*.json").forEach { path ->
            val session = readSession(path)
            if (session.profileId == profile.id) {
                writeAtomic(
                    path,
                    session.copy(
                        profileId = UserProfiles.DEFAULT_ID,
                        updatedAt = Instant.now().toString(),
                    ),
                )
            }
        }
        Files.delete(profileFile(profile.id))
    }

    @Synchronized
    fun appendTurn(sessionId: String, userText: String, answer: String) {
        val session = requireSession(sessionId)
        val now = Instant.now().toString()
        val messages = session.messages + listOf(
            ChatMessage(UUID.randomUUID().toString(), "user", userText, now),
            ChatMessage(UUID.randomUUID().toString(), "assistant", answer, now),
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
    private fun profileFile(id: String): Path = profilesDirectory.resolve("${validateProfileId(id)}.json")

    private fun readSession(path: Path): SessionDocument {
        val session = gson.fromJson(path.readText(), SessionDocument::class.java)
        val profileId = usableProfileId(session.profileId)
        if (session.profileId == profileId) return session
        return session.copy(profileId = profileId).also { migrated -> writeAtomic(path, migrated) }
    }

    private fun readProfile(path: Path): UserProfile = gson.fromJson(path.readText(), UserProfile::class.java)

    private fun requireProfile(id: String): UserProfile {
        val path = profileFile(id)
        require(path.exists()) { "Профиль '$id' не найден." }
        return readProfile(path)
    }

    private fun readMemories(path: Path): List<MemoryItem> {
        if (!path.exists()) return emptyList()
        return gson.fromJson(path.readText(), MemoryDocument::class.java)?.items.orEmpty()
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

    private fun validateProfileId(value: String): String {
        val normalized = value.trim()
        require(normalized.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) {
            "Некорректный идентификатор профиля."
        }
        return normalized
    }

    private fun validateProfileField(label: String, value: String, maxLength: Int): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "$label профиля не должно быть пустым." }
        require(normalized.length <= maxLength) { "$label профиля не должно превышать $maxLength символов." }
        return normalized
    }

    private fun validateConstraints(values: List<String?>): List<String> {
        val normalized = values.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }.distinct()
        require(normalized.size <= 10) { "В профиле допускается не более 10 ограничений." }
        normalized.forEach { constraint ->
            require(constraint.length <= 500) { "Ограничение не должно превышать 500 символов." }
        }
        return normalized
    }

    private fun usableProfileId(value: String?): String {
        val candidate = value?.trim().orEmpty()
        if (!candidate.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) return UserProfiles.DEFAULT_ID
        return if (profilesDirectory.resolve("$candidate.json").exists()) {
            candidate
        } else {
            UserProfiles.DEFAULT_ID
        }
    }

    private fun titleFrom(text: String): String {
        val oneLine = text.trim().replace(Regex("\\s+"), " ")
        return if (oneLine.length <= 42) oneLine else oneLine.take(39) + "…"
    }

    private fun SessionDocument.summary() = SessionSummary(id, title, updatedAt, messages.size)
}
