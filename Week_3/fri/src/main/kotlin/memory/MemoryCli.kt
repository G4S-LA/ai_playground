package memory

class MemoryCli(private val agent: MemoryAgent) {
    private var sessionId: String = agent.listSessions().firstOrNull()?.id ?: agent.createSession().id

    fun run() {
        println("Агент с контролируемым жизненным циклом задачи. /help — список команд.")
        printSession()
        while (true) {
            print("Вы: ")
            val input = readlnOrNull() ?: break
            if (input.isBlank()) continue
            try {
                if (input.startsWith("/")) {
                    if (!execute(input)) break
                } else {
                    val result = agent.reply(sessionId, input) { state ->
                        println("[${state.stage}] шаг ${state.currentStep} · ${state.expectedAction}")
                    }
                    println("Агент: ${result.answer}")
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
            "/state" -> printState()
            "/approve" -> {
                val result = agent.approvePlan(sessionId) { state ->
                    println("[${state.stage}] шаг ${state.currentStep} · ${state.expectedAction}")
                }
                println("Агент: ${result.answer}")
            }
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

    private fun printState() {
        val state = agent.snapshot(sessionId).taskState
        println(
            "Состояние: этап=${state.stage}, шаг=${state.currentStep}, " +
                "ожидается=${state.expectedAction}"
        )
        println("Разрешённые события: ${state.allowedEvents.joinToString().ifEmpty { "нет" }}")
        if (state.stage == TaskStage.PLANNING.wireName && state.planReady) {
            println("План готов: /approve — утвердить, обычное сообщение — отправить замечания.")
        }
        if (state.stage == TaskStage.IDLE.wireName) {
            println("Новый запрос обязательно начнёт цикл с planning.")
        } else if (state.stage == TaskStage.DONE.wireName) {
            println("Следующий запрос начнёт новый цикл с planning.")
        } else if (state.stage == TaskStage.BLOCKED.wireName) {
            println("Причина: ${state.lastFailure}. Следующее сообщение возобновит задачу.")
        } else if (state.stage == TaskStage.FAILED.wireName) {
            println("Причина: ${state.lastFailure}. Можно повторить запрос или начать новую задачу.")
        }
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
        val snapshot = agent.snapshot(sessionId)
        val session = snapshot.session
        println("Диалог: ${session.title} (${session.id})")
        println("Этап задачи: ${snapshot.taskState.stage}")
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
              /state                  показать этап, шаг и ожидаемое действие
              /approve                утвердить готовый план и начать выполнение
              /new                    новый диалог и новая рабочая память
              /sessions               список диалогов
              /use <session-id>       открыть диалог
              /delete                 удалить текущий диалог и его рабочую память
              /exit                   выход

            Новый запрос сначала создаёт план и останавливается в planning.
            До /approve обычные сообщения считаются замечаниями к плану.
            Happy path после утверждения: execution → validation → done.
            Red path: validation → execution либо blocked; техническая ошибка → failed.
            Каждый переход выполняется только по разрешённому событию.
            """.trimIndent()
        )
    }
}
