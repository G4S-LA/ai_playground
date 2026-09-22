package gitmcp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal class ChatAgent(
    private val model: LanguageModel,
    private val tools: ToolGateway,
    private val store: FileChatStore,
    private val systemPrompt: String,
    private val repositoryPath: String,
    private val modelName: String,
    private val gson: Gson = Gson(),
) {
    private val mutex = Mutex()

    fun listSessions(): List<SessionSummary> = store.listSessions()

    fun createSession(): SessionSummary = store.createSession()

    fun snapshot(sessionId: String): ChatSnapshot = ChatSnapshot(store.snapshot(sessionId), modelName)

    fun deleteSession(sessionId: String) = store.deleteSession(sessionId)

    suspend fun reply(sessionId: String, rawMessage: String): AgentReply = mutex.withLock {
        val userMessage = rawMessage.trim()
        require(userMessage.isNotEmpty()) { "Enter a non-empty message." }
        require(userMessage.length <= 4_000) { "Message must not exceed 4000 characters." }

        tools.withSession {
            val availableTools = listTools()
            val session = store.snapshot(sessionId)
            val messages = mutableListOf(
                AgentMessage(
                    role = "system",
                    content = "$systemPrompt\nDefault Git repository path: $repositoryPath",
                ),
            ).apply {
                addAll(session.messages.map { AgentMessage(role = it.role, content = it.content) })
                add(AgentMessage(role = "user", content = userMessage))
            }
            val executions = mutableListOf<ToolExecution>()

            repeat(4) {
                val turn = model.complete(messages, availableTools)
                if (turn.toolCalls.isEmpty()) {
                    val answer = turn.content.trim()
                    require(answer.isNotEmpty()) { "The model returned an empty answer." }
                    store.appendTurn(sessionId, userMessage, answer)
                    return@withSession AgentReply(answer, executions)
                }

                messages += AgentMessage(
                    role = "assistant",
                    content = turn.content,
                    toolCalls = turn.toolCalls,
                )
                turn.toolCalls.forEach { call ->
                    val arguments = parseArguments(call.argumentsJson).toMutableMap()
                    if (call.name == GIT_SUMMARY_TOOL) {
                        arguments.putIfAbsent("repositoryPath", repositoryPath)
                    }
                    val result = callTool(call.name, arguments)
                    executions += ToolExecution(call.name, result)
                    messages += AgentMessage(
                        role = "tool",
                        content = result,
                        toolCallId = call.id,
                    )
                }
            }
            throw AgentException("The agent exceeded the tool-call limit.")
        }
    }

    suspend fun availableTools(): List<AgentTool> = tools.withSession { listTools() }

    private fun parseArguments(json: String): Map<String, Any?> {
        val root = try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (error: Exception) {
            throw AgentException("The model returned invalid tool arguments.", error)
        }
        return root.entrySet().associate { (name, value) -> name to value.toKotlinValue() }
    }

    private fun JsonElement.toKotlinValue(): Any? = when {
        isJsonNull -> null
        isJsonObject -> asJsonObject.entrySet().associate { (key, value) -> key to value.toKotlinValue() }
        isJsonArray -> asJsonArray.map { it.toKotlinValue() }
        asJsonPrimitive.isBoolean -> asBoolean
        asJsonPrimitive.isString -> asString
        asJsonPrimitive.isNumber -> asBigDecimal.let { number ->
            if (number.stripTrailingZeros().scale() <= 0) number.toLong() else number.toDouble()
        }
        else -> asString
    }
}
