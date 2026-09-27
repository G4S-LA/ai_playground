package multimcp

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    // MCP reserves stdout for JSON-RPC in server mode.
    KotlinLoggingConfiguration.logStartupMessage = false
    runBlocking { runApplication(args) }
}

private suspend fun runApplication(args: Array<String>) {
    val command = args.firstOrNull { !it.startsWith("--") }
        ?: if ("--help" in args || "-h" in args) "help" else "demo"
    if (command == "help") {
        printUsage()
        return
    }
    val demo = command == "demo" || "--demo" in args
    val config = AppConfig.fromEnvironment(demo = demo)

    val model: LanguageModel = if (demo) {
        DemoMultiMcpLanguageModel()
    } else {
        OpenAiCompatibleModel(config)
    }
    val agent = MultiMcpAgent(model, MultiMcpGateway(config), config.outputDirectory)
    if (command == "web") {
        runWeb(agent, config)
        return
    }
    val request = when (command) {
        "demo" -> "Получи свежие Hacker News и курсы USD к EUR, GBP и JPY. " +
            "Сохрани их в новый Excel-файл, оформи таблицу и проверь книгу."
        "run" -> args.drop(1).filterNot { it == "--demo" }.joinToString(" ").ifBlank {
            error("After 'run', specify an agent request.")
        }
        else -> error("Unknown command '$command'. Start with 'help'.")
    }
    val result = agent.run(request)
    result.executions.forEachIndexed { index, execution ->
        println("${index + 1}. ${execution.serverName} → ${execution.name}")
    }
    println(result.answer)
}

private fun printUsage() {
    println(
        """
        Usage:
          ./gradlew :run --args=demo
          ./gradlew :run --args="web --demo"
          ./gradlew :run --args=web
          ./gradlew :run --args="run Собери новости и курсы валют в Excel"
          ./gradlew :test
        """.trimIndent(),
    )
}
