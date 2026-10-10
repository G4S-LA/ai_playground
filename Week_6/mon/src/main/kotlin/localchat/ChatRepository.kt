package localchat

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

class ChatStorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ChatRepository(private val databasePath: Path) {
    init {
        try {
            databasePath.toAbsolutePath().parent?.let(Files::createDirectories)
            connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode = WAL")
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS chats (
                            id TEXT PRIMARY KEY,
                            title TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS messages (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            chat_id TEXT NOT NULL,
                            role TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
                            content TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    statement.execute("CREATE INDEX IF NOT EXISTS messages_chat_id_id ON messages(chat_id, id)")
                }
            }
        } catch (error: Exception) {
            throw ChatStorageException("Не удалось открыть SQLite-базу $databasePath: ${error.message}", error)
        }
    }

    @Synchronized
    fun createChat(): ChatSnapshot {
        val id = UUID.randomUUID().toString()
        val now = now()
        sql("Не удалось создать диалог") {
            connect().use { connection ->
                connection.prepareStatement(
                    "INSERT INTO chats(id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, id)
                    statement.setString(2, DEFAULT_TITLE)
                    statement.setString(3, now)
                    statement.setString(4, now)
                    statement.executeUpdate()
                }
            }
        }
        return getChat(id) ?: throw ChatStorageException("Созданный диалог не найден.")
    }

    @Synchronized
    fun listChats(): List<ChatSummary> = sql("Не удалось прочитать список диалогов") {
        connect().use { connection ->
            connection.prepareStatement(
                """
                SELECT c.id, c.title, c.created_at, c.updated_at, COUNT(m.id) AS message_count
                FROM chats c
                LEFT JOIN messages m ON m.chat_id = c.id
                GROUP BY c.id
                ORDER BY c.updated_at DESC, c.created_at DESC
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.toSummary()) }
                }
            }
        }
    }

    @Synchronized
    fun getChat(id: String): ChatSnapshot? = sql("Не удалось прочитать диалог") {
        connect().use { connection ->
            val chat = connection.prepareStatement(
                "SELECT id, title, created_at, updated_at FROM chats WHERE id = ?",
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { row ->
                    if (!row.next()) return@sql null
                    ChatSnapshot(
                        id = row.getString("id"),
                        title = row.getString("title"),
                        createdAt = row.getString("created_at"),
                        updatedAt = row.getString("updated_at"),
                        messages = emptyList(),
                    )
                }
            }
            chat.copy(messages = loadMessages(connection, id))
        }
    }

    @Synchronized
    fun appendTurn(chatId: String, userContent: String, assistantContent: String): ChatSnapshot =
        sql("Не удалось сохранить ответ модели") {
            connect().use { connection ->
                connection.autoCommit = false
                try {
                    val currentTitle = connection.prepareStatement(
                        "SELECT title FROM chats WHERE id = ?",
                    ).use { statement ->
                        statement.setString(1, chatId)
                        statement.executeQuery().use { row ->
                            if (!row.next()) throw NoSuchElementException("Диалог '$chatId' не найден.")
                            row.getString("title")
                        }
                    }
                    val isFirstTurn = connection.prepareStatement(
                        "SELECT NOT EXISTS(SELECT 1 FROM messages WHERE chat_id = ?)",
                    ).use { statement ->
                        statement.setString(1, chatId)
                        statement.executeQuery().use { row -> row.next() && row.getBoolean(1) }
                    }
                    val timestamp = now()
                    connection.prepareStatement(
                        "INSERT INTO messages(chat_id, role, content, created_at) VALUES (?, ?, ?, ?)",
                    ).use { statement ->
                        listOf("user" to userContent, "assistant" to assistantContent).forEach { (role, content) ->
                            statement.setString(1, chatId)
                            statement.setString(2, role)
                            statement.setString(3, content)
                            statement.setString(4, timestamp)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    connection.prepareStatement(
                        "UPDATE chats SET title = ?, updated_at = ? WHERE id = ?",
                    ).use { statement ->
                        val title = if (isFirstTurn && currentTitle == DEFAULT_TITLE) titleFrom(userContent) else currentTitle
                        statement.setString(1, title)
                        statement.setString(2, timestamp)
                        statement.setString(3, chatId)
                        statement.executeUpdate()
                    }
                    connection.commit()
                } catch (error: Exception) {
                    connection.rollback()
                    throw error
                }
            }
            getChat(chatId) ?: throw ChatStorageException("Сохранённый диалог не найден.")
        }

    @Synchronized
    fun deleteChat(id: String) {
        sql("Не удалось удалить диалог") {
            connect().use { connection ->
                connection.prepareStatement("DELETE FROM chats WHERE id = ?").use { statement ->
                    statement.setString(1, id)
                    if (statement.executeUpdate() == 0) throw NoSuchElementException("Диалог '$id' не найден.")
                }
            }
        }
    }

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}").also {
        it.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA busy_timeout = 5000")
        }
    }

    private fun loadMessages(connection: Connection, chatId: String): List<StoredMessage> =
        connection.prepareStatement(
            "SELECT id, role, content, created_at FROM messages WHERE chat_id = ? ORDER BY id",
        ).use { statement ->
            statement.setString(1, chatId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            StoredMessage(
                                rows.getLong("id"),
                                rows.getString("role"),
                                rows.getString("content"),
                                rows.getString("created_at"),
                            ),
                        )
                    }
                }
            }
        }

    private fun ResultSet.toSummary() = ChatSummary(
        id = getString("id"),
        title = getString("title"),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
        messageCount = getInt("message_count"),
    )

    private fun now(): String = java.time.Instant.now().toString()

    private fun titleFrom(message: String): String {
        val normalized = message.trim().replace(Regex("\\s+"), " ")
        return if (normalized.length <= MAX_TITLE_LENGTH) normalized else normalized.take(MAX_TITLE_LENGTH - 1).trimEnd() + "…"
    }

    private inline fun <T> sql(action: String, block: () -> T): T = try {
        block()
    } catch (error: ChatStorageException) {
        throw error
    } catch (error: NoSuchElementException) {
        throw error
    } catch (error: SQLException) {
        throw ChatStorageException("$action: ${error.message}", error)
    }

    companion object {
        const val DEFAULT_TITLE = "Новый диалог"
        const val MAX_TITLE_LENGTH = 60
    }
}
