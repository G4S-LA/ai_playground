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
    val model: String?,
    val reachable: Boolean,
    val installed: Boolean,
    val availableModels: List<String> = emptyList(),
    val error: String? = null,
)

data class HealthResponse(
    val status: String,
    val ready: Boolean,
    val service: String = "private-local-chat",
    val model: String,
    val ollama: ModelStatus,
)

data class ChatListResponse(val chats: List<ChatSummary>)
data class ChatResponse(val chat: ChatSnapshot)
data class SendMessageRequest(val message: String = "")
data class ApiErrorDetail(val message: String, val type: String = "local_chat_error", val code: String)
data class ApiErrorResponse(val error: ApiErrorDetail)
