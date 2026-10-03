package groundedrag

import docindex.ChunkStrategy
import docindex.DemoEmbeddingProvider
import docindex.DocumentLoader
import docindex.EmbeddingProvider
import docindex.IndexingService
import docindex.OllamaEmbeddingProvider
import docindex.SqliteVectorIndex
import enhancedrag.HeuristicQueryRewriter
import enhancedrag.LlmQueryRewriter
import enhancedrag.QueryRewriter
import enhancedrag.RetrievalSettings
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
    val embeddings: EmbeddingProvider = if (demo) DemoEmbeddingProvider() else {
        OllamaEmbeddingProvider(config.ollamaUrl, config.embeddingModel)
    }
    val index = IndexingService(DocumentLoader(config.documentsDir), embeddings, SqliteVectorIndex(config.databasePath))
    val questions = ControlQuestionRepository()
    if (command == "index") {
        println(index.build(ChunkStrategy.STRUCTURED))
        return@runBlocking
    }
    if (command == "questions") {
        questions.questions.forEach { println("${it.id}. ${it.question}") }
        return@runBlocking
    }

    val chat = chatModel(config)
    val rewriter: QueryRewriter = if (demo) HeuristicQueryRewriter() else LlmQueryRewriter(chat)
    val service = GroundedRagService(index, chat, rewriter)
    val judge = SemanticSupportJudge(chat)
    when (command) {
        "web" -> runWeb(service, judge, questions, config)
        "answer" -> {
            val question = arguments.drop(1).joinToString(" ").ifBlank { error("Укажите вопрос.") }
            println(service.answer(question, RetrievalSettings()))
        }
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
    Grounded RAG

      ./gradlew run --args=web
      ./gradlew run --args="web --demo"
      ./gradlew run --args=index
      ./gradlew run --args="answer Что такое агентный RAG?"
      ./gradlew run --args=questions
      ./gradlew test
    """.trimIndent(),
)
