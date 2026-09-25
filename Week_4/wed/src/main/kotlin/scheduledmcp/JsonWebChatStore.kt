package scheduledmcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.UUID
import kotlin.io.path.exists

internal class JsonWebChatStore(
    private val file: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
) {
    init {
        if (!file.exists()) write(WebChatState())
    }

    @Synchronized
    fun create(): WebChatSession {
        val now = clock.instant().toString()
        val session = WebChatSession(
            id = UUID.randomUUID().toString(),
            title = "Новый диалог",
            createdAt = now,
            updatedAt = now,
        )
        val state = read()
        write(state.copy(sessions = state.sessions + session))
        return session
    }

    @Synchronized
    fun list(): List<WebSessionSummary> = read().sessions
        .sortedByDescending { it.updatedAt }
        .map { WebSessionSummary(it.id, it.title, it.updatedAt, it.messages.size) }

    @Synchronized
    fun get(id: String): WebChatSession = read().sessions.firstOrNull { it.id == id }
        ?: error("Диалог '$id' не найден.")

    @Synchronized
    fun addMessage(id: String, role: String, content: String, scheduleId: String? = null): WebChatSession {
        require(content.isNotBlank()) { "Сообщение не должно быть пустым." }
        val state = read()
        val existing = state.sessions.firstOrNull { it.id == id }
            ?: error("Диалог '$id' не найден.")
        val now = clock.instant().toString()
        val message = WebChatMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            content = content.trim(),
            createdAt = now,
            scheduleId = scheduleId,
        )
        val title = if (existing.messages.isEmpty() && role == "user") {
            content.trim().replace(Regex("\\s+"), " ").take(60)
        } else {
            existing.title
        }
        val updated = existing.copy(
            title = title,
            updatedAt = now,
            messages = existing.messages + message,
        )
        write(state.copy(sessions = state.sessions.map { if (it.id == id) updated else it }))
        return updated
    }

    @Synchronized
    fun addReports(reports: List<ExecutedReport>) {
        if (reports.isEmpty()) return
        var state = read()
        reports.forEach { report ->
            if (state.sessions.any { session -> session.messages.any { it.runId == report.due.run.id } }) {
                return@forEach
            }
            val session = state.sessions.firstOrNull { candidate ->
                candidate.messages.any { it.scheduleId == report.due.schedule.id }
            } ?: return@forEach
            val now = clock.instant().toString()
            val message = WebChatMessage(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = report.report,
                createdAt = now,
                scheduleId = report.due.schedule.id,
                runId = report.due.run.id,
            )
            val updated = session.copy(updatedAt = now, messages = session.messages + message)
            state = state.copy(sessions = state.sessions.map { if (it.id == session.id) updated else it })
        }
        write(state)
    }

    @Synchronized
    fun delete(id: String) {
        val state = read()
        require(state.sessions.any { it.id == id }) { "Диалог '$id' не найден." }
        write(state.copy(sessions = state.sessions.filterNot { it.id == id }))
    }

    private fun read(): WebChatState {
        if (!file.exists()) return WebChatState()
        val source = Files.readString(file)
        return if (source.isBlank()) WebChatState() else gson.fromJson(source, WebChatState::class.java)
    }

    private fun write(state: WebChatState) {
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, gson.toJson(state))
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
