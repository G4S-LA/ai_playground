package gitmcp

import kotlinx.coroutines.runBlocking

internal class ChatCli(private val agent: ChatAgent) {
    private var sessionId: String = agent.listSessions().firstOrNull()?.id ?: agent.createSession().id

    fun run() {
        println("Git MCP agent. /help shows chat commands.")
        printSession()
        while (true) {
            print("You: ")
            val input = readlnOrNull() ?: break
            if (input.isBlank()) continue
            try {
                when (input.trim()) {
                    "/exit", "/quit" -> return
                    "/help" -> printHelp()
                    "/new" -> {
                        sessionId = agent.createSession().id
                        printSession()
                    }
                    "/sessions" -> printSessions()
                    "/delete" -> {
                        agent.deleteSession(sessionId)
                        sessionId = agent.listSessions().firstOrNull()?.id ?: agent.createSession().id
                        println("Chat deleted.")
                        printSession()
                    }
                    else -> if (input.startsWith("/use ")) {
                        val target = input.substringAfter(' ').trim()
                        agent.snapshot(target)
                        sessionId = target
                        printSession()
                    } else runBlocking {
                        val reply = agent.reply(sessionId, input)
                        reply.toolExecutions.forEach { println("[MCP] Called ${it.name}") }
                        println("Agent: ${reply.answer}")
                    }
                }
            } catch (error: Exception) {
                println("Error: ${error.message}")
            }
        }
    }

    private fun printSessions() {
        agent.listSessions().forEach { session ->
            val marker = if (session.id == sessionId) "*" else " "
            println("$marker ${session.id} · ${session.title} · messages: ${session.messageCount}")
        }
    }

    private fun printSession() {
        val session = agent.snapshot(sessionId).session
        println("Chat: ${session.title} (${session.id})")
    }

    private fun printHelp() {
        println(
            """
            Commands:
              /new               create a chat
              /sessions          list chats
              /use <chat-id>     switch chat
              /delete            delete current chat
              /exit              quit
            """.trimIndent(),
        )
    }
}
