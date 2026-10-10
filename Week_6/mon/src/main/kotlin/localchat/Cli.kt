package localchat

import kotlinx.coroutines.runBlocking

fun runCli(service: LocalChatService) = runBlocking {
    var current = service.listChats().firstOrNull()?.let { service.getChat(it.id) } ?: service.createChat()
    println("Локальный чат · Ollama · ${service.modelName}")
    println("Диалог: ${current.title} (${current.messages.size} сообщений)")
    printCliHelp()

    while (true) {
        print("\nВы: ")
        System.out.flush()
        val input = readlnOrNull()?.trim() ?: return@runBlocking
        when {
            input.isBlank() -> Unit
            input == "/exit" -> return@runBlocking
            input == "/help" -> printCliHelp()
            input == "/new" -> {
                current = service.createChat()
                println("Создан новый диалог: ${current.id}")
            }
            input == "/chats" -> printChats(service, current.id)
            input.startsWith("/use ") -> {
                val id = input.removePrefix("/use ").trim()
                current = service.getChat(id)
                println("Открыт диалог «${current.title}». Восстановлено сообщений: ${current.messages.size}.")
            }
            input == "/delete" -> {
                service.deleteChat(current.id)
                current = service.listChats().firstOrNull()?.let { service.getChat(it.id) } ?: service.createChat()
                println("Диалог удалён. Открыт «${current.title}».")
            }
            else -> {
                try {
                    val reply = service.send(current.id, input)
                    current = reply.chat
                    println("\nМодель: ${reply.message.content}")
                } catch (error: ModelUnavailableException) {
                    System.err.println("Ошибка: ${error.message}")
                }
            }
        }
    }
}

private fun printChats(service: LocalChatService, currentId: String) {
    val chats = service.listChats()
    if (chats.isEmpty()) {
        println("Сохранённых диалогов нет.")
        return
    }
    chats.forEach { chat ->
        val marker = if (chat.id == currentId) "*" else " "
        println("$marker ${chat.id} · ${chat.title} · ${chat.messageCount} сообщ.")
    }
    println("Переключение: /use <id>")
}

private fun printCliHelp() {
    println("Команды: /new, /chats, /use <id>, /delete, /help, /exit")
}
