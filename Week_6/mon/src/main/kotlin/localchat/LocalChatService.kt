package localchat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalChatService(
    private val repository: ChatRepository,
    private val model: ChatModel,
    private val systemPrompt: String,
) {
    private val generationMutex = Mutex()

    val modelName: String get() = model.modelName
    val endpoint: String get() = model.endpoint

    fun listChats(): List<ChatSummary> = repository.listChats()

    fun createChat(): ChatSnapshot = repository.createChat()

    fun getChat(id: String): ChatSnapshot = repository.getChat(id)
        ?: throw NoSuchElementException("Диалог '$id' не найден.")

    fun deleteChat(id: String) = repository.deleteChat(id)

    suspend fun modelStatus(): ModelStatus = model.status()

    suspend fun send(chatId: String, rawMessage: String): ChatReply = generationMutex.withLock {
        val message = rawMessage.trim()
        require(message.isNotEmpty()) { "Введите сообщение." }
        require(message.length <= MAX_MESSAGE_LENGTH) {
            "Сообщение не должно превышать $MAX_MESSAGE_LENGTH символов."
        }

        val chat = getChat(chatId)
        val context = buildList {
            add(ChatMessage("system", systemPrompt))
            addAll(chat.messages.map { ChatMessage(it.role, it.content) })
            add(ChatMessage("user", message))
        }
        val answer = model.complete(context)
        val updated = repository.appendTurn(chatId, message, answer)
        ChatReply(updated, updated.messages.last())
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 4_000
    }
}
