package gitmcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.name

internal class FileChatStore(
    private val directory: Path,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
) {
    init {
        Files.createDirectories(directory)
    }

    @Synchronized
    fun listSessions(): List<SessionSummary> = Files.list(directory).use { paths ->
        paths
            .filter { it.name.endsWith(".json") }
            .map(::readSession)
            .map { it.toSummary() }
            .sorted(Comparator.comparing<SessionSummary, String> { it.updatedAt }.reversed())
            .toList()
    }

    @Synchronized
    fun createSession(): SessionSummary {
        val now = Instant.now().toString()
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Новый диалог",
            createdAt = now,
            updatedAt = now,
        )
        writeSession(session)
        return session.toSummary()
    }

    @Synchronized
    fun snapshot(sessionId: String): ChatSession = readSession(pathFor(sessionId))

    @Synchronized
    fun appendTurn(sessionId: String, userMessage: String, assistantMessage: String): ChatSession {
        val session = snapshot(sessionId)
        val now = Instant.now().toString()
        val updated = session.copy(
            title = if (session.messages.isEmpty()) userMessage.take(60) else session.title,
            updatedAt = now,
            messages = session.messages + listOf(
                ChatEntry(role = "user", content = userMessage, createdAt = now),
                ChatEntry(role = "assistant", content = assistantMessage),
            ),
        )
        writeSession(updated)
        return updated
    }

    @Synchronized
    fun deleteSession(sessionId: String) {
        val path = pathFor(sessionId)
        require(path.exists()) { "Chat '$sessionId' does not exist." }
        Files.delete(path)
    }

    private fun pathFor(sessionId: String): Path {
        val normalized = try {
            UUID.fromString(sessionId).toString()
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid chat identifier.", error)
        }
        return directory.resolve("$normalized.json")
    }

    private fun readSession(path: Path): ChatSession {
        require(path.exists()) { "Chat '${path.fileName.toString().removeSuffix(".json")}' does not exist." }
        return Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
            gson.fromJson(reader, ChatSession::class.java)
        }
    }

    private fun writeSession(session: ChatSession) {
        val target = pathFor(session.id)
        val temporary = target.resolveSibling("${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.newBufferedWriter(temporary, Charsets.UTF_8).use { writer ->
                gson.toJson(session, writer)
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun ChatSession.toSummary(): SessionSummary = SessionSummary(
        id = id,
        title = title,
        updatedAt = updatedAt,
        messageCount = messages.size,
    )
}
