package toolpipeline

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal class PipelineAgent(
    private val model: LanguageModel,
    private val gateway: ToolGateway,
    private val gson: Gson = Gson(),
) {
    suspend fun run(
        request: String,
        trace: (PipelineTraceEvent) -> Unit = {},
    ): PipelineRun {
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
            trace(
                PipelineTraceEvent(
                    type = "pipeline_started",
                    message = "Агент получил ${tools.size} MCP tools и начал пайплайн",
                    payload = request,
                ),
            )

            repeat(MAX_AGENT_TURNS) {
                val previousTool = executions.lastOrNull()?.name
                trace(
                    PipelineTraceEvent(
                        type = "model_started",
                        message = if (previousTool == null) {
                            "Модель анализирует запрос и выбирает первый tool"
                        } else {
                            "Модель анализирует результат $previousTool и выбирает следующий шаг"
                        },
                        payload = "Доступны schemas: ${tools.joinToString { tool -> tool.name }}",
                    ),
                )
                val turn = model.complete(messages, tools)
                if (turn.toolCalls.isEmpty()) {
                    trace(
                        PipelineTraceEvent(
                            type = "model_completed",
                            message = "Модель завершила цепочку и сформировала ответ",
                            payload = turn.content,
                        ),
                    )
                    require(turn.content.isNotBlank()) { "Model returned an empty final answer." }
                    val actualOrder = executions.map(ToolExecution::name)
                    if (actualOrder != pipelineOrder) {
                        throw PipelineException(
                            "Agent finished with an invalid pipeline: ${actualOrder.joinToString(" -> ")}. " +
                                "Expected ${pipelineOrder.joinToString(" -> ")}.",
                        )
                    }
                    trace(
                        PipelineTraceEvent(
                            type = "pipeline_completed",
                            message = "Цепочка search → summarize → saveToFile завершена",
                        ),
                    )
                    return@withSession PipelineRun(turn.content.trim(), executions)
                }
                val call = turn.toolCalls.singleOrNull()
                    ?: throw PipelineException("A sequential pipeline must call exactly one tool per turn.")
                require(call.name in availableNames) { "Model requested unknown tool '${call.name}'." }
                val arguments = parseArguments(call.argumentsJson)
                trace(
                    PipelineTraceEvent(
                        type = "model_tool_selected",
                        message = "Модель выбрала ${call.name}",
                        toolName = call.name,
                        payload = call.argumentsJson,
                    ),
                )
                messages += AgentMessage(role = "assistant", content = turn.content, toolCalls = listOf(call))
                trace(
                    PipelineTraceEvent(
                        type = "tool_started",
                        message = "MCP выполняет ${call.name}",
                        toolName = call.name,
                        payload = gson.toJson(arguments),
                    ),
                )
                val toolResult = callTool(call.name, arguments)
                trace(
                    PipelineTraceEvent(
                        type = "tool_completed",
                        message = "Результат ${call.name} возвращён модели",
                        toolName = call.name,
                        payload = toolResult,
                    ),
                )
                executions += ToolExecution(call.name, arguments, toolResult)
                messages += AgentMessage(role = "tool", content = toolResult, toolCallId = call.id)
            }
            throw PipelineException("Agent exceeded the limit of $MAX_AGENT_TURNS turns.")
        }
    }

    suspend fun availableTools(): List<AgentTool> = gateway.withSession { listTools() }

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
