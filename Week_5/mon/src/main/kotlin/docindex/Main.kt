package docindex

import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val demo = "--demo" in args
    val arguments = args.filterNot { it == "--demo" }
    val command = arguments.firstOrNull() ?: "web"
    if (command in setOf("help", "--help", "-h")) {
        printUsage()
        return@runBlocking
    }

    val config = AppConfig.fromEnvironment(demo)
    val provider: EmbeddingProvider = when (config.embeddingProvider.lowercase()) {
        "demo" -> DemoEmbeddingProvider()
        "ollama" -> OllamaEmbeddingProvider(config.ollamaUrl, config.ollamaModel)
        else -> error("EMBEDDING_PROVIDER должен быть ollama или demo.")
    }
    val index = SqliteVectorIndex(config.databasePath)
    val service = IndexingService(DocumentLoader(config.documentsDir), provider, index)
    val store = UploadedDocumentStore(config.documentsDir)

    when (command) {
        "web" -> runWeb(service, store, config)
        "index" -> {
            val requested = arguments.getOrNull(1) ?: "both"
            val strategies = if (requested == "both") ChunkStrategy.entries else listOf(ChunkStrategy.parse(requested))
            strategies.forEach { strategy -> println(service.build(strategy)) }
        }
        "search" -> {
            val strategy = ChunkStrategy.parse(arguments.getOrNull(1) ?: "structured")
            val query = arguments.drop(2).joinToString(" ").ifBlank { error("Укажите поисковый запрос.") }
            service.search(query, strategy).forEachIndexed { index, hit ->
                println("${index + 1}. ${"%.4f".format(hit.score)} · ${hit.source} · ${hit.section}")
                println(hit.text.take(300).replace('\n', ' '))
            }
        }
        else -> error("Неизвестная команда '$command'. Запустите help.")
    }
}

private fun printUsage() {
    println(
        """
        Локальный индекс документов

          ./gradlew run --args="web --demo"       Web UI без Ollama
          ./gradlew run --args=web                Web UI с Ollama
          ./gradlew run --args="index both"       Построить оба индекса
          ./gradlew run --args="search structured как запустить проект"
          ./gradlew test
        """.trimIndent(),
    )
}
