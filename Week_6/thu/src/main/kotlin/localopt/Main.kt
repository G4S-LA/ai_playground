package localopt

import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    try {
        val config = AppConfig.load()
        val service = ComparisonService(
            model = OllamaClient(config.ollamaUrl, config.model, config.timeoutSeconds),
            baselineProfile = config.baseline,
            optimizedProfile = config.optimized,
        )
        when (val command = args.firstOrNull()?.lowercase() ?: "web") {
            "web" -> runWeb(service, config)
            "compare" -> compareOnce(service, args.drop(1).joinToString(" "))
            "check" -> checkModel(service)
            "help", "--help", "-h" -> printUsage()
            else -> error("Неизвестная команда '$command'. Запустите help.")
        }
    } catch (error: Exception) {
        System.err.println("Ошибка: ${error.message}")
        if (System.getenv("DEBUG") == "1") error.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

private fun compareOnce(service: ComparisonService, rawPrompt: String) = runBlocking {
    val prompt = rawPrompt.trim().ifBlank {
        print("Запрос для сравнения: ")
        System.out.flush()
        readlnOrNull()?.trim().orEmpty()
    }
    val result = service.compare(prompt)
    println("Порядок выполнения: ${result.executionOrder.joinToString(" → ")}")
    printRun(result.baseline)
    printRun(result.optimized)
}

private fun printRun(run: ComparedRun) {
    println("\n=== ${run.profile.label} ===")
    println("temperature=${run.profile.temperature}, max_tokens=${run.profile.maxTokens}, num_ctx=${run.profile.contextWindow}")
    println(run.answer)
    println(
        "total=${run.metrics.totalMs} ms, generation=${run.metrics.generationMs} ms, " +
            "tokens=${run.metrics.responseTokens}, speed=${"%.1f".format(run.metrics.tokensPerSecond)} tok/s",
    )
}

private fun checkModel(service: ComparisonService) = runBlocking {
    val info = service.info().model
    check(info.reachable) { info.error ?: "Ollama недоступна." }
    check(info.installed) { "Модель ${info.model} не установлена. Выполните: ollama pull ${info.model}" }
    println("OK · ${info.provider} отвечает локально: ${info.endpoint}")
    println("OK · модель: ${info.model} · ${info.parameterSize ?: "?"} · ${info.quantization ?: "quantization unknown"}")
}

private fun printUsage() {
    println(
        """
        Оптимизация локальной LLM через Ollama

          ./gradlew run --args=check
          ./gradlew run --args="compare Объясни, что такое корутины Kotlin"
          ./gradlew run --args=web
          ./gradlew test
        """.trimIndent(),
    )
}
