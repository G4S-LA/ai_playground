package localchat

import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    try {
        val config = AppConfig.load()
        val model = OllamaChatModel(
            endpoint = config.ollamaUrl,
            modelName = config.ollamaModel,
            temperature = config.temperature,
            timeoutSeconds = config.timeoutSeconds,
        )

        when (val command = args.firstOrNull()?.lowercase() ?: "web") {
            "web" -> runWeb(createService(config, model), config)
            "cli" -> runCli(createService(config, model))
            "ask" -> askOnce(model, config, args.drop(1).joinToString(" "))
            "check" -> checkLocalModel(model, config)
            "help", "--help", "-h" -> printUsage()
            else -> error("Неизвестная команда '$command'. Запустите help.")
        }
    } catch (error: Exception) {
        System.err.println("Ошибка: ${error.message}")
        if (System.getenv("DEBUG") == "1") error.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

private fun createService(config: AppConfig, model: ChatModel) = LocalChatService(
    repository = ChatRepository(config.databasePath),
    model = model,
    systemPrompt = config.systemPrompt,
)

private fun askOnce(model: ChatModel, config: AppConfig, rawPrompt: String) = runBlocking {
    val prompt = rawPrompt.trim().ifBlank {
        print("Ваш запрос: ")
        System.out.flush()
        readlnOrNull()?.trim().orEmpty()
    }
    require(prompt.isNotEmpty()) { "Запрос не должен быть пустым." }
    println(model.complete(listOf(ChatMessage("system", config.systemPrompt), ChatMessage("user", prompt))))
}

private fun checkLocalModel(model: ChatModel, config: AppConfig) = runBlocking {
    val status = model.status()
    check(status.reachable) { status.error ?: "Ollama недоступна." }
    check(status.installed) {
        "Модель ${status.model} не установлена. Выполните: ollama pull ${status.model}. " +
            "Доступны: ${status.availableModels.ifEmpty { listOf("нет моделей") }.joinToString()}"
    }
    println("OK · Ollama отвечает локально: ${status.endpoint}")
    println("OK · Модель установлена: ${status.model}")
    val prompt = "Ответь только одним числом: сколько будет два плюс два?"
    val answer = model.complete(listOf(ChatMessage("system", config.systemPrompt), ChatMessage("user", prompt)))
    println("OK · Простой запрос: $prompt")
    println("Ответ модели: $answer")
}

private fun printUsage() {
    println(
        """
        Локальная LLM через Ollama

          ./gradlew run --args=check                  Проверить API, модель и простой ответ
          ./gradlew run --args="ask Привет!"          Один запрос из CLI
          ./gradlew run --args=cli                    Интерактивный CLI с историей
          ./gradlew run --args=web                    Web UI и HTTP API
          ./gradlew test                              Автотесты без запущенной модели
        """.trimIndent(),
    )
}
