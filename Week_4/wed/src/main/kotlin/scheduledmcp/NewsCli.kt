package scheduledmcp

internal class NewsCli(
    private val agent: NewsAgent,
    private val delivery: ReportDelivery,
) {
    suspend fun run() {
        println("Агент новостных отчётов")
        println("Напишите, например: Собирай топ новостей за сутки каждый день в 18:00")
        println("Команды: /run-due, /list, /history, /exit")

        while (true) {
            print("> ")
            val input = readlnOrNull()?.trim() ?: break
            if (input.isEmpty()) continue
            try {
                when (input) {
                    "/exit", "/quit" -> return
                    "/run-due" -> {
                        val reports = agent.runDue()
                        if (reports.isEmpty()) println("Заданий, готовых к запуску, нет.")
                        reports.forEach(delivery::deliver)
                    }
                    "/list" -> println(agent.listSchedules())
                    "/history" -> println(agent.history())
                    else -> println(agent.schedule(input))
                }
            } catch (error: Exception) {
                System.err.println("Ошибка: ${error.message}")
            }
        }
    }
}
