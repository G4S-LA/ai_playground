package memory

class MemoryCli(private val agent: MemoryAgent) {
    private var sessionId: String = agent.listSessions().firstOrNull()?.id ?: agent.createSession().id

    fun run() {
        println("Агент с явными слоями памяти. /help — список команд.")
        printSession()
        while (true) {
            print("Вы: ")
            val input = readlnOrNull() ?: break
            if (input.isBlank()) continue
            try {
                if (input.startsWith("/")) {
                    if (!execute(input)) break
                } else {
                    println("Агент: ${agent.reply(sessionId, input).answer}")
                }
            } catch (error: Exception) {
                println("Ошибка: ${error.message}")
            }
        }
    }

    internal fun execute(rawCommand: String): Boolean {
        val command = rawCommand.trim()
        val name = command.substringBefore(' ')
        val tail = command.substringAfter(' ', "").trim()
        when (name) {
            "/help" -> printHelp()
            "/new" -> {
                sessionId = agent.createSession().id
                printSession()
            }
            "/sessions" -> printSessions()
            "/use" -> {
                require(tail.isNotEmpty()) { "Формат: /use <session-id>" }
                agent.snapshot(tail)
                sessionId = tail
                printSession()
            }
            "/delete" -> {
                agent.deleteSession(sessionId)
                sessionId = agent.listSessions().firstOrNull()?.id ?: agent.createSession().id
                println("Диалог удалён.")
                printSession()
            }
            "/remember" -> remember(tail)
            "/memory" -> printMemory(tail.ifBlank { "all" })
            "/forget" -> forget(tail)
            "/exit", "/quit" -> return false
            else -> throw IllegalArgumentException("Неизвестная команда '$name'. Используйте /help.")
        }
        return true
    }

    private fun remember(arguments: String) {
        val parts = arguments.split(Regex("\\s+"), limit = 3)
        require(parts.size == 3) {
            "Формат: /remember <short_term|working|long_term> <category> <text>"
        }
        val result = agent.remember(sessionId, parts[0], parts[1], parts[2])
        println("Сохранено в ${parts[0]}: ${result.memory.id}")
    }

    private fun forget(arguments: String) {
        val parts = arguments.split(Regex("\\s+"), limit = 2)
        require(parts.size == 2) { "Формат: /forget <layer> <memory-id>" }
        agent.forget(sessionId, parts[0], parts[1])
        println("Запись удалена из ${parts[0]}.")
    }

    private fun printSessions() {
        agent.listSessions().forEach {
            val marker = if (it.id == sessionId) "*" else " "
            println("$marker ${it.id} · ${it.title} · сообщений: ${it.messageCount}")
        }
    }

    private fun printMemory(layerName: String) {
        val snapshot = agent.snapshot(sessionId)
        if (layerName in setOf("all", "short", "short_term")) {
            println("\nКраткосрочная память · текущий диалог:")
            if (snapshot.session.messages.isEmpty()) println("  (пусто)")
            snapshot.session.messages.forEach {
                val category = it.category?.let { value -> " · $value" }.orEmpty()
                println("  ${it.id} · ${it.role}$category · ${it.content}")
            }
        }
        if (layerName in setOf("all", "work", "working")) {
            printItems("Рабочая память · текущая задача", snapshot.working)
        }
        if (layerName in setOf("all", "long", "long_term")) {
            printItems("Долговременная память · общая", snapshot.longTerm)
        }
        require(layerName in setOf("all", "short", "short_term", "work", "working", "long", "long_term")) {
            "Неизвестный слой '$layerName'."
        }
        println()
    }

    private fun printItems(title: String, items: List<MemoryItem>) {
        println("\n$title:")
        if (items.isEmpty()) println("  (пусто)")
        items.forEach { println("  ${it.id} · ${it.category} · ${it.content}") }
    }

    private fun printSession() {
        val session = agent.snapshot(sessionId).session
        println("Диалог: ${session.title} (${session.id})")
    }

    private fun printHelp() {
        println(
            """
            Команды:
              /remember short_term dialogue_note <текст>
              /remember working <goal|context|constraint|note> <текст>
              /remember long_term <profile|decision|knowledge> <текст>
              /memory [all|short_term|working|long_term]
              /forget <layer> <memory-id>
              /new                    новый диалог и новая рабочая память
              /sessions               список диалогов
              /use <session-id>       открыть диалог
              /delete                 удалить текущий диалог и его рабочую память
              /exit                   выход

            Обычный текст отправляется модели и сохраняется только в краткосрочной памяти.
            """.trimIndent()
        )
    }
}
