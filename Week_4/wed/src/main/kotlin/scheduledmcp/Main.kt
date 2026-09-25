package scheduledmcp

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    // stdout MCP-сервера зарезервирован для JSON-RPC.
    KotlinLoggingConfiguration.logStartupMessage = false
    runBlocking { runApplication(args) }
}

private suspend fun runApplication(args: Array<String>) {
    val command = args.firstOrNull { !it.startsWith("--") }
        ?: if ("--help" in args || "-h" in args) "help" else "cli"
    if (command == "help") {
        printUsage()
        return
    }
    val demoModel = command == "demo" || "--demo" in args
    val config = AppConfig.fromEnvironment(demo = demoModel || command == "server")

    if (command == "server") {
        runNewsMcpServer(config)
        return
    }

    val model: LanguageModel = if (demoModel) DemoNewsLanguageModel() else OpenAiCompatibleModel(config)
    val agent = NewsAgent(model, NewsMcpGateway(config), config.defaultTimeZone)
    val delivery = ConsoleAndWebhookDelivery(config.reportWebhookUrl)

    when (command) {
        "cli" -> NewsCli(agent, delivery).run()
        "web" -> runWeb(agent, delivery, config)
        "run-due" -> executeDue(agent, delivery)
        "list" -> println(agent.listSchedules())
        "history" -> println(agent.history())
        "demo" -> {
            println(agent.scheduleOnceNowForDemo())
            executeDue(agent, delivery)
        }
        else -> error("Неизвестная команда '$command'. Запустите с аргументом help.")
    }
}

private suspend fun executeDue(agent: NewsAgent, delivery: ReportDelivery) {
    val reports = agent.runDue()
    if (reports.isEmpty()) {
        println("Заданий, готовых к запуску, нет.")
    } else {
        reports.forEach(delivery::deliver)
    }
}

private fun printUsage() {
    println(
        """
        Использование:
          ./gradlew run --args="cli [--demo]"      диалог с агентом и постановка задания
          ./gradlew run --args="web [--demo]"      Web-интерфейс агента
          ./gradlew run --args="run-due [--demo]" выполнить готовые задания (команда для cron)
          ./gradlew run --args="list --demo"       показать расписания
          ./gradlew run --args="history --demo"    показать агрегированную историю
          ./gradlew run --args=demo                 создать и сразу выполнить демонстрационное задание
          ./gradlew run --args=server               запустить MCP-сервер по stdio
        """.trimIndent(),
    )
}
