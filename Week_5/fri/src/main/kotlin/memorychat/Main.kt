package memorychat

import docindex.ChunkStrategy
import docindex.DemoEmbeddingProvider
import docindex.DocumentLoader
import docindex.EmbeddingProvider
import docindex.IndexingService
import docindex.OllamaEmbeddingProvider
import docindex.SqliteVectorIndex
import groundedrag.GroundedRagService
import kotlinx.coroutines.runBlocking
import ragagent.ChatLanguageModel
import ragagent.DemoChatLanguageModel
import ragagent.OllamaChatLanguageModel
import ragagent.OpenAiCompatibleChatLanguageModel
import ragagent.RagConfig

fun main(args: Array<String>) = runBlocking {
    val demo = "--demo" in args
    val arguments = args.filterNot { it == "--demo" }
    val command = arguments.firstOrNull() ?: "web"
    if (command in setOf("help", "--help", "-h")) {
        printUsage()
        return@runBlocking
    }

    val config = RagConfig.fromEnvironment(demo, defaultPort = 5000)
    val embeddings: EmbeddingProvider = if (demo) DemoEmbeddingProvider() else {
        OllamaEmbeddingProvider(config.ollamaUrl, config.embeddingModel)
    }
    val index = IndexingService(DocumentLoader(config.documentsDir), embeddings, SqliteVectorIndex(config.databasePath))
    if (command == "index") {
        println(index.build(ChunkStrategy.STRUCTURED))
        return@runBlocking
    }

    val model = chatModel(config)
    val grounded = GroundedRagService(index, model, TaggedQueryRewriter())
    val chat = MemoryChatService(
        memoryPlanner = LlmTaskMemoryPlanner(model),
        answerer = GroundedAnswerer(grounded::answer),
    )
    when (command) {
        "web" -> runWeb(chat, grounded, config)
        else -> error("Неизвестная команда '$command'. Запустите help.")
    }
}

private fun chatModel(config: RagConfig): ChatLanguageModel = when {
    config.demo -> DemoChatLanguageModel()
    config.llmProvider == "api" -> OpenAiCompatibleChatLanguageModel(
        apiKey = requireNotNull(config.apiKey) { "Для LLM_PROVIDER=api задайте LLM_API_KEY." },
        apiUrl = config.apiUrl,
        model = config.apiModel,
    )
    config.llmProvider == "ollama" -> OllamaChatLanguageModel(config.ollamaUrl, config.ollamaChatModel)
    else -> error("LLM_PROVIDER должен быть api или ollama.")
}

private fun printUsage() = println(
    """
    RAG-чат с памятью задачи

      ./gradlew run --args=web
      ./gradlew run --args="web --demo"
      ./gradlew run --args=index
      ./gradlew test
    """.trimIndent(),
)
