package enhancedrag

import docindex.ChunkStrategy
import docindex.DemoEmbeddingProvider
import docindex.DocumentLoader
import docindex.EmbeddingProvider
import docindex.IndexingService
import docindex.OllamaEmbeddingProvider
import docindex.SqliteVectorIndex
import kotlinx.coroutines.runBlocking
import ragagent.ChatLanguageModel
import ragagent.ControlQuestionRepository
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

    val config = RagConfig.fromEnvironment(demo)
    val embeddings: EmbeddingProvider = if (demo) {
        DemoEmbeddingProvider()
    } else {
        OllamaEmbeddingProvider(config.ollamaUrl, config.embeddingModel)
    }
    val indexing = IndexingService(
        DocumentLoader(config.documentsDir),
        embeddings,
        SqliteVectorIndex(config.databasePath),
    )
    val questions = ControlQuestionRepository()

    if (command == "index") {
        println(indexing.build(ChunkStrategy.STRUCTURED))
        return@runBlocking
    }
    if (command == "questions") {
        questions.questions.forEach { println("${it.id}. ${it.question}") }
        return@runBlocking
    }

    val chat = chatModel(config)
    val rewriter: QueryRewriter = if (demo) HeuristicQueryRewriter() else LlmQueryRewriter(chat)
    val service = EnhancedRagService(indexing, chat, rewriter)
    when (command) {
        "web" -> runWeb(service, questions, config)
        "compare" -> {
            val question = arguments.drop(1).joinToString(" ").ifBlank { error("Укажите вопрос.") }
            val result = service.compare(question)
            println("БЕЗ ОПТИМИЗАЦИИ:\n${result.baseline.answer}\n")
            println("С QUERY REWRITE + FILTER + RERANK:\n${result.enhanced.answer}\n")
            println("Переписанный запрос: ${result.enhanced.searchQuery}")
            println("Кандидатов: ${result.enhanced.candidates.size}; после фильтра: ${result.enhanced.sources.size}")
        }
        else -> error("Неизвестная команда '$command'. Запустите help.")
    }
}

private fun chatModel(config: RagConfig): ChatLanguageModel = when {
    config.demo -> DemoChatLanguageModel()
    config.llmProvider == "api" -> OpenAiCompatibleChatLanguageModel(
        apiKey = requireNotNull(config.apiKey) {
            "Для LLM_PROVIDER=api задайте LLM_API_KEY (или DASHSCOPE_API_KEY)."
        },
        apiUrl = config.apiUrl,
        model = config.apiModel,
    )
    config.llmProvider == "ollama" -> OllamaChatLanguageModel(config.ollamaUrl, config.ollamaChatModel)
    else -> error("LLM_PROVIDER должен быть api или ollama.")
}

private fun printUsage() {
    println(
        """
        Enhanced RAG

          ./gradlew run --args=web
          ./gradlew run --args="web --demo"
          ./gradlew run --args=index
          ./gradlew run --args="compare Чем агентный RAG отличается от обычного?"
          ./gradlew run --args=questions
          ./gradlew test
        """.trimIndent(),
    )
}
