package localchat

class FakeChatModel(
    private val answer: String = "Тестовый локальный ответ",
    private val failWith: ModelUnavailableException? = null,
) : ChatModel {
    override val modelName: String = "qwen-test:latest"
    override val endpoint: String = "http://127.0.0.1:11434"
    val requests = mutableListOf<List<ChatMessage>>()

    override suspend fun complete(messages: List<ChatMessage>): String {
        failWith?.let { throw it }
        requests += messages
        return answer
    }

    override suspend fun status() = ModelStatus(
        endpoint = endpoint,
        model = modelName,
        reachable = true,
        installed = true,
        availableModels = listOf(modelName),
    )
}
