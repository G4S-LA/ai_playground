package localchat

class FakeChatModel(
    private val answer: String = "Ответ модели",
) : ChatModel {
    override val endpoint: String = "http://127.0.0.1:11434"
    val requests = mutableListOf<List<ChatMessage>>()

    override suspend fun complete(messages: List<ChatMessage>): String {
        requests += messages
        return answer
    }

    override suspend fun status() = ModelStatus(
        endpoint = endpoint,
        model = "test-model",
        reachable = true,
        installed = true,
        availableModels = listOf("test-model"),
    )
}
