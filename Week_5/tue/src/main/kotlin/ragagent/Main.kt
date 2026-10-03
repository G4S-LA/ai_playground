package ragagent

import docindex.DemoEmbeddingProvider
import docindex.DocumentLoader
import docindex.EmbeddingProvider
import docindex.IndexingService
import docindex.OllamaEmbeddingProvider
import docindex.SqliteVectorIndex
import docindex.ChunkStrategy
import kotlinx.coroutines.runBlocking

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
        questions.questions.forEach { question ->
            println("${question.id}. ${question.question}")
            println("   Ожидание: ${question.expectation}")
            println("   Источники: ${question.expectedSources.joinToString()}")
        }
        return@runBlocking
    }

    val agent = RagAgentService(indexing, chatModel(config))
    when (command) {
        "web" -> runWeb(agent, questions, config)
        "compare" -> {
            val question = arguments.drop(1).joinToString(" ").ifBlank { error("Укажите вопрос.") }
            val result = agent.compare(question)
            println("БЕЗ RAG:\n${result.withoutRag.answer}\n")
            println("С RAG:\n${result.withRag.answer}\n")
            result.withRag.sources.forEach { println("[${it.citation}] ${it.source} · ${it.section}") }
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
    config.llmProvider == "ollama" -> OllamaChatLanguageModel(
        config.ollamaUrl,
        config.ollamaChatModel,
    )
    else -> error("LLM_PROVIDER должен быть api или ollama.")
}

private fun printUsage() {
    println(
        """
        RAG comparison agent

          ./gradlew run --args=web
          ./gradlew run --args="web --demo"
          ./gradlew run --args=index
          ./gradlew run --args="compare Что такое агентный RAG?"
          ./gradlew run --args=questions
          ./gradlew test
        """.trimIndent(),
    )
}
