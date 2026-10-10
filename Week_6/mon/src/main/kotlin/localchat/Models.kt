package localchat

data class ChatMessage(
    val role: String,
    val content: String,
)

data class StoredMessage(
    val id: Long,
    val role: String,
    val content: String,
    val createdAt: String,
)

data class ChatSummary(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val messageCount: Int,
)

data class ChatSnapshot(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val messages: List<StoredMessage>,
)

data class ModelStatus(
    val provider: String = "Ollama",
    val endpoint: String,
    val model: String,
    val reachable: Boolean,
    val installed: Boolean,
    val availableModels: List<String> = emptyList(),
    val error: String? = null,
)

data class ChatReply(
    val chat: ChatSnapshot,
    val message: StoredMessage,
)
