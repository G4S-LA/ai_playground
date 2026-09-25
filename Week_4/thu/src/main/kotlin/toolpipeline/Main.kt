package toolpipeline

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    // MCP reserves stdout for JSON-RPC while running in server mode.
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
    val config = AppConfig.fromEnvironment(demo = demo || command == "server")
    if (command == "server") {
        runPipelineMcpServer(config)
        return
    }

    val model: LanguageModel = if (demo) DemoPipelineLanguageModel() else OpenAiCompatibleModel(config)
    val agent = PipelineAgent(model, PipelineMcpGateway(config))
    if (command == "web") {
        runWeb(agent, config)
        return
    }
    val request = when (command) {
        "demo" -> "Найди материалы про MCP tool schemas, сделай сводку и сохрани её в pipeline-report.md"
        "run" -> args.drop(1).filterNot { it == "--demo" }.joinToString(" ").ifBlank {
            error("After 'run', specify a pipeline request.")
        }
        else -> error("Unknown command '$command'. Start with 'help'.")
    }
    val result = agent.run(request)
    result.executions.forEachIndexed { index, execution ->
        println("${index + 1}. ${execution.name}")
    }
    println(result.answer)
}

private fun printUsage() {
    println(
        """
        Usage:
          ./gradlew run --args=demo
          ./gradlew run --args="web --demo"
          ./gradlew run --args="run Найди данные про MCP, сделай сводку и сохрани её в report.md"
          ./gradlew run --args=server
          ./gradlew test
        """.trimIndent(),
    )
}
