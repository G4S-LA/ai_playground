package localrag

class FakeLocalChatModel(
    private val answer: String = "RAG объединяет retrieval и generation [S1].",
) : LocalChatModel {
    override val description: String = "Ollama · test-local"
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun generate(systemPrompt: String, userPrompt: String): String {
        calls += systemPrompt to userPrompt
        return answer
    }

    override suspend fun status() = OllamaStatus(
        endpoint = "http://127.0.0.1:11434",
        reachable = true,
        availableModels = listOf("test-embed:latest", "test-local:latest"),
    )
}
