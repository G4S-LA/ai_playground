package localchat

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    configureConsoleEncoding()
    try {
        when (val command = args.firstOrNull()?.lowercase() ?: "web") {
            "init" -> AppConfig.initializeDotenv()
            "web" -> {
                val config = AppConfig.load()
                runWeb(createService(config), config)
            }
            "check" -> {
                val config = AppConfig.load()
                checkLocalModel(createModel(config))
            }
            "help", "--help", "-h" -> printUsage()
            else -> error("Неизвестная команда '$command'. Запустите help.")
        }
    } catch (error: Exception) {
        System.err.println("Ошибка: ${error.message}")
        if (System.getenv("DEBUG") == "1") error.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

private fun createModel(config: AppConfig) = OllamaChatModel(
    endpoint = config.ollamaUrl,
    configuredModel = config.model,
    timeoutSeconds = config.ollamaTimeoutSeconds,
)

private fun createService(config: AppConfig) = LocalChatService(
    repository = ChatRepository(config.databasePath),
    model = createModel(config),
)

private fun checkLocalModel(model: ChatModel) = runBlocking {
    val status = model.status()
    check(status.reachable) { status.error ?: "Ollama недоступна." }
    check(status.installed) { status.error ?: "В Ollama нет доступной chat-модели." }
    println("OK · Ollama отвечает: ${status.endpoint}")
    println("OK · Модель: ${status.model}")
    val answer = model.complete(listOf(ChatMessage("user", "Ответь одним числом: сколько будет два плюс два?")))
    println("OK · Ответ модели: $answer")
}

private fun configureConsoleEncoding() {
    System.setOut(PrintStream(System.out, true, StandardCharsets.UTF_8))
    System.setErr(PrintStream(System.err, true, StandardCharsets.UTF_8))
}

private fun printUsage() {
    println(
        """
        Локальный чат на Kotlin

          ./gradlew run --args=init    Создать .env со случайным API-ключом
          ./gradlew run                Запустить Web UI и HTTP API
          ./gradlew run --args=check   Проверить Ollama и выбранную модель
          ./gradlew test               Запустить автотесты
        """.trimIndent(),
    )
}
