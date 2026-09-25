package toolpipeline

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal class PipelineAgent(
    private val model: LanguageModel,
    private val gateway: ToolGateway,
    private val gson: Gson = Gson(),
) {
    suspend fun run(request: String): PipelineRun {
        require(request.isNotBlank()) { "request must not be blank" }
        return gateway.withSession {
            val tools = listTools()
            val pipelineOrder = listOf(SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TOOL)
            val availableNames = tools.map(AgentTool::name).toSet()
            require(availableNames.containsAll(pipelineOrder)) {
                "MCP server must expose ${pipelineOrder.joinToString(" -> ")}"
            }
            val messages = mutableListOf(
                AgentMessage(
                    role = "system",
                    content = "Ты запускаешь обязательный пайплайн search → summarize → saveToFile. " +
                        "Вызывай ровно один инструмент за ход и строго в этом порядке. " +
                        "После search передай весь JSON-результат без изменений в searchResult инструмента summarize. " +
                        "После summarize передай весь JSON-результат без изменений в summary инструмента saveToFile. " +
                        "Не выдумывай данные между этапами.",
                ),
                AgentMessage(role = "user", content = request),
            )
            val executions = mutableListOf<ToolExecution>()

            repeat(MAX_AGENT_TURNS) {
                val turn = model.complete(messages, tools)
                if (turn.toolCalls.isEmpty()) {
                    require(turn.content.isNotBlank()) { "Model returned an empty final answer." }
                    val actualOrder = executions.map(ToolExecution::name)
                    if (actualOrder != pipelineOrder) {
                        throw PipelineException(
                            "Agent finished with an invalid pipeline: ${actualOrder.joinToString(" -> ")}. " +
                                "Expected ${pipelineOrder.joinToString(" -> ")}.",
                        )
                    }
                    return@withSession PipelineRun(turn.content.trim(), executions)
                }
                val call = turn.toolCalls.singleOrNull()
                    ?: throw PipelineException("A sequential pipeline must call exactly one tool per turn.")
                require(call.name in availableNames) { "Model requested unknown tool '${call.name}'." }
                val arguments = parseArguments(call.argumentsJson)
                messages += AgentMessage(role = "assistant", content = turn.content, toolCalls = listOf(call))
                val toolResult = callTool(call.name, arguments)
                executions += ToolExecution(call.name, arguments, toolResult)
                messages += AgentMessage(role = "tool", content = toolResult, toolCallId = call.id)
            }
            throw PipelineException("Agent exceeded the limit of $MAX_AGENT_TURNS turns.")
        }
    }

    private fun parseArguments(json: String): Map<String, Any?> {
        val root = try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (error: Exception) {
            throw PipelineException("Model returned invalid tool arguments.", error)
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

    private companion object {
        const val MAX_AGENT_TURNS = 6
    }
}
