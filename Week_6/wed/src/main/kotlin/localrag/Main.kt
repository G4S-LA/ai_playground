package localrag

import docindex.ChunkStrategy
import docindex.DocumentLoader
import docindex.IndexingService
import docindex.SqliteVectorIndex
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    try {
        val command = args.firstOrNull()?.lowercase() ?: "web"
        if (command in setOf("help", "--help", "-h")) {
            printUsage()
            return@runBlocking
        }

        val config = LocalRagConfig.load()
        val model = OllamaChatModel(
            baseUrl = config.ollamaUrl,
            model = config.chatModel,
            temperature = config.temperature,
            timeoutSeconds = config.timeoutSeconds,
        )
        val indexing = IndexingService(
            loader = DocumentLoader(config.documentsDir),
            embeddings = LocalOllamaEmbeddingProvider(
                config.ollamaUrl,
                config.embeddingModel,
                config.timeoutSeconds,
            ),
            index = SqliteVectorIndex(config.databasePath),
        )
        val service = LocalRagService(indexing, model)

        when (command) {
            "web" -> runWeb(service, config)
            "index" -> println(indexing.build(ChunkStrategy.STRUCTURED))
            "ask" -> {
                val question = args.drop(1).joinToString(" ").ifBlank { error("Укажите вопрос.") }
                printAnswer(service.answer(question))
            }
            "check" -> checkPipeline(service, config)
            else -> error("Неизвестная команда '$command'. Запустите help.")
        }
    } catch (error: Exception) {
        System.err.println("Ошибка: ${error.message}")
        if (System.getenv("DEBUG") == "1") error.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

private suspend fun checkPipeline(service: LocalRagService, config: LocalRagConfig) {
    val status = service.modelStatus()
    check(status.reachable) { status.error ?: "Ollama недоступна." }
    check(status.hasModel(config.embeddingModel)) {
        "Embedding-модель ${config.embeddingModel} не установлена. Выполните: ollama pull ${config.embeddingModel}"
    }
    check(status.hasModel(config.chatModel)) {
        "Chat-модель ${config.chatModel} не установлена. Выполните: ollama pull ${config.chatModel}"
    }
    val structured = service.stats().first { it.strategy == ChunkStrategy.STRUCTURED.wireName }
    check(structured.chunks > 0) { "Structured-индекс пуст. Выполните: ./gradlew run --args=index" }

    println("OK · Ollama доступна локально: ${status.endpoint}")
    println("OK · Embeddings: ${config.embeddingModel}")
    println("OK · Generation: ${config.chatModel}")
    println("OK · Индекс: ${structured.chunks} чанков из ${structured.documents} документов")
    printAnswer(service.answer("Что такое RAG и из каких этапов он состоит?", topK = 3))
}

private fun printAnswer(result: LocalRagAnswer) {
    println(result.answer)
    println()
    result.sources.forEach { source ->
        println("[${source.citation}] ${source.source} · ${source.section} · score=${"%.3f".format(source.score)}")
    }
    println("retrieval=${result.retrievalMs} ms · generation=${result.generationMs} ms · total=${result.totalMs} ms")
}

private fun printUsage() {
    println(
        """
        Полностью локальный RAG

          ./gradlew run --args=check
          ./gradlew run --args=index
          ./gradlew run --args="ask Что такое RAG?"
          ./gradlew run --args=web
          ./gradlew test
        """.trimIndent(),
    )
}
