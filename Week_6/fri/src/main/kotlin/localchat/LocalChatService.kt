package localchat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalChatService(
    private val repository: ChatRepository,
    private val model: ChatModel,
) {
    private val generationMutex = Mutex()

    fun listChats(): List<ChatSummary> = repository.listChats()

    fun createChat(): ChatSnapshot = repository.createChat()

    fun getChat(id: String): ChatSnapshot = repository.getChat(id)
        ?: throw NoSuchElementException("Диалог '$id' не найден.")

    fun deleteChat(id: String) = repository.deleteChat(id)

    suspend fun health(): HealthResponse {
        val modelStatus = model.status()
        return HealthResponse(
            status = if (modelStatus.reachable && modelStatus.installed) "ok" else "degraded",
            ready = modelStatus.reachable && modelStatus.installed,
            model = modelStatus.model ?: "не выбрана",
            ollama = modelStatus,
        )
    }

    suspend fun send(chatId: String, rawMessage: String): ChatSnapshot = generationMutex.withLock {
        val message = rawMessage.trim()
        require(message.isNotEmpty()) { "Введите сообщение." }
        val chat = getChat(chatId)
        val history = buildList {
            addAll(chat.messages.map { ChatMessage(it.role, it.content) })
            add(ChatMessage("user", message))
        }
        val answer = model.complete(history)
        repository.appendTurn(chatId, message, answer)
    }
}
