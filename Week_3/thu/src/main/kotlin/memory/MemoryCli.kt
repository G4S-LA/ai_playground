package memory

class MemoryCli(private val agent: MemoryAgent) {
    private var sessionId: String = agent.listSessions().firstOrNull()?.id ?: agent.createSession().id

    fun run() {
        println("Агент с обязательными инвариантами. /help — список команд.")
        printSession()
        while (true) {
            print("Вы: ")
            val input = readlnOrNull() ?: break
            if (input.isBlank()) continue
            try {
                if (input.startsWith("/")) {
                    if (!execute(input)) break
                } else {
                    val result = agent.reply(sessionId, input)
                    println("Агент: ${result.answer}")
                    printCompliance(result.compliance)
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
                println("Диалог удалён. Глобальные инварианты сохранены.")
                printSession()
            }
            "/remember" -> remember(tail)
            "/memory" -> printMemory(tail.ifBlank { "all" })
            "/forget" -> forget(tail)
            "/invariant" -> invariant(tail)
            "/invariants" -> printInvariants()
            "/exit", "/quit" -> return false
            else -> throw IllegalArgumentException("Неизвестная команда '$name'. Используйте /help.")
        }
        return true
    }

    private fun invariant(arguments: String) {
        val action = arguments.substringBefore(' ')
        val tail = arguments.substringAfter(' ', "").trim()
        when (action) {
            "add" -> {
                val parts = tail.split(Regex("\\s+"), limit = 2)
                require(parts.size == 2) { "Формат: /invariant add <category> <text>" }
                val result = agent.addInvariant(sessionId, parts[0], parts[1])
                println("Инвариант добавлен: ${result.invariant.id}")
            }
            "remove" -> {
                require(tail.isNotEmpty()) { "Формат: /invariant remove <id>" }
                agent.removeInvariant(sessionId, tail)
                println("Инвариант удалён.")
            }
            else -> throw IllegalArgumentException("Допустимо: /invariant add ... или /invariant remove ...")
        }
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
            println("\nКраткосрочная память:")
            snapshot.session.messages.orEmpty().forEach { println("  ${it.id} · ${it.role} · ${it.content}") }
        }
        if (layerName in setOf("all", "work", "working")) printItems("Рабочая память", snapshot.working)
        if (layerName in setOf("all", "long", "long_term")) printItems("Долговременная память", snapshot.longTerm)
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

    private fun printInvariants() {
        val invariants = agent.snapshot(sessionId).invariants
        println("\nОбязательные инварианты (глобальные, не память):")
        if (invariants.isEmpty()) println("  (пусто)")
        invariants.forEach { println("  ${it.id} · ${it.category} · ${it.content}") }
        println()
    }

    private fun printCompliance(report: ComplianceReport) {
        println("Проверка инвариантов: ${report.status} — ${report.summary}")
        report.checks.forEach { println("  ${it.invariantId} · ${it.status} · ${it.explanation}") }
    }

    private fun printSession() {
        val session = agent.snapshot(sessionId).session
        println("Диалог: ${session.title} (${session.id})")
    }

    private fun printHelp() {
        println(
            """
            Команды:
              /invariant add <architecture|technical_decision|stack_constraint|business_rule> <текст>
              /invariant remove <id>
              /invariants
              /remember short_term dialogue_note <текст>
              /remember working <goal|context|constraint|note> <текст>
              /remember long_term <profile|decision|knowledge> <текст>
              /memory [all|short_term|working|long_term]
              /forget <layer> <memory-id>
              /new · /sessions · /use <session-id> · /delete · /exit

            Инварианты глобальны, хранятся отдельно от памяти и проверяются перед каждым ответом.
            """.trimIndent()
        )
    }
}
