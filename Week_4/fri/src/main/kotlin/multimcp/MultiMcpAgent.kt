package multimcp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

internal const val WORKBOOK_CONTEXT_PREFIX = "AVAILABLE_EXCEL_OUTPUT_PATH="

internal class MultiMcpAgent(
    private val model: LanguageModel,
    private val gateway: ToolGateway,
    private val outputDirectory: Path,
    private val gson: Gson = Gson(),
) {
    suspend fun run(
        request: String,
        trace: (AgentTraceEvent) -> Unit = {},
    ): AgentRun {
        require(request.isNotBlank()) { "request must not be blank" }
        return gateway.withSession {
            val servers = listServers()
            val tools = listTools()
            val toolsByName = tools.associateBy(AgentTool::name)
            require(tools.isNotEmpty()) { "MCP registry returned no tools." }
            require(servers.map(McpServerInfo::id).toSet() == MCP_SERVERS.map(McpServerInfo::id).toSet()) {
                "MCP registry must connect news, currency and Excel servers."
            }

            val workbookPath = createUniqueWorkbookPath()
            val messages = mutableListOf(
                AgentMessage(
                    role = "system",
                    content = SYSTEM_PROMPT,
                ),
                AgentMessage(role = "system", content = WORKBOOK_CONTEXT_PREFIX + workbookPath),
                AgentMessage(role = "user", content = request),
            )
            val executions = mutableListOf<ToolExecution>()
            val workbooksCreatedInThisRun = mutableSetOf<Path>()
            trace(
                AgentTraceEvent(
                    type = "pipeline_started",
                    message = "Агент подключил ${servers.size} MCP-сервера и получил ${tools.size} tools",
                    payload = request,
                ),
            )

            repeat(MAX_AGENT_TURNS) {
                val previous = executions.lastOrNull()
                trace(
                    AgentTraceEvent(
                        type = "model_started",
                        message = if (previous == null) {
                            "Модель анализирует задачу и выбирает подходящий инструмент"
                        } else {
                            "Модель анализирует результат ${previous.serverName} · ${previous.name}"
                        },
                        payload = tools.joinToString("\n") { tool ->
                            "${tool.serverName}: ${tool.name}"
                        },
                    ),
                )
                val turn = model.complete(messages, tools)
                if (turn.toolCalls.isEmpty()) {
                    require(turn.content.isNotBlank()) { "Model returned an empty final answer." }
                    trace(
                        AgentTraceEvent(
                            type = "model_completed",
                            message = "Модель сформировала итоговый ответ",
                            payload = turn.content,
                        ),
                    )
                    trace(
                        AgentTraceEvent(
                            type = "pipeline_completed",
                            message = "Агент завершил запрос: ${executions.size} MCP-вызовов, " +
                                "${executions.map(ToolExecution::serverId).distinct().size} серверов использовано",
                        ),
                    )
                    return@withSession AgentRun(turn.content.trim(), executions)
                }

                if (executions.size + turn.toolCalls.size > MAX_TOOL_CALLS) {
                    throw AgentException("Agent exceeded the limit of $MAX_TOOL_CALLS tool calls.")
                }
                messages += AgentMessage(role = "assistant", content = turn.content, toolCalls = turn.toolCalls)
                turn.toolCalls.forEach { call ->
                    val tool = toolsByName[call.name]
                        ?: throw AgentException("Model requested unknown tool '${call.name}'.")
                    val arguments = applySafetyPolicy(
                        tool = tool,
                        arguments = parseArguments(call.argumentsJson),
                        workbookPath = workbookPath,
                        workbooksCreatedInThisRun = workbooksCreatedInThisRun,
                    )
                    trace(
                        AgentTraceEvent(
                            type = "model_tool_selected",
                            message = "Модель выбрала ${tool.name} из ${tool.serverName}",
                            toolName = tool.name,
                            serverId = tool.serverId,
                            serverName = tool.serverName,
                            payload = call.argumentsJson,
                        ),
                    )
                    trace(
                        AgentTraceEvent(
                            type = "tool_started",
                            message = "Маршрутизатор отправил tools/call в ${tool.serverName}",
                            toolName = tool.name,
                            serverId = tool.serverId,
                            serverName = tool.serverName,
                            payload = gson.toJson(arguments),
                        ),
                    )
                    val result = callTool(call.name, arguments)
                    if (call.name == CREATE_WORKBOOK_TOOL) {
                        (arguments["filepath"] as? String)?.let { path ->
                            workbooksCreatedInThisRun.add(Path.of(path).toAbsolutePath().normalize())
                        }
                    }
                    trace(
                        AgentTraceEvent(
                            type = "tool_completed",
                            message = "${tool.serverName} вернул результат модели",
                            toolName = tool.name,
                            serverId = tool.serverId,
                            serverName = tool.serverName,
                            payload = result,
                        ),
                    )
                    executions += ToolExecution(
                        name = tool.name,
                        serverId = tool.serverId,
                        serverName = tool.serverName,
                        arguments = arguments,
                        result = result,
                    )
                    messages += AgentMessage(role = "tool", content = result, toolCallId = call.id)
                }
            }
            throw AgentException("Agent exceeded the limit of $MAX_AGENT_TURNS turns.")
        }
    }

    suspend fun availableTopology(): McpTopology = gateway.withSession {
        McpTopology(listServers(), listTools())
    }

    private fun parseArguments(json: String): Map<String, Any?> {
        val root = try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (error: Exception) {
            throw AgentException("Model returned invalid tool arguments.", error)
        }
        return root.entrySet().associate { (name, value) -> name to value.toKotlinValue() }
    }

    private fun applySafetyPolicy(
        tool: AgentTool,
        arguments: Map<String, Any?>,
        workbookPath: String,
        workbooksCreatedInThisRun: Set<Path>,
    ): Map<String, Any?> {
        if (tool.serverId != EXCEL_SERVER) return arguments

        val requestedPath = (arguments["filepath"] as? String)
            ?.takeIf(String::isNotBlank)
            ?.let { value -> runCatching { Path.of(value) }.getOrNull() }
            ?.takeIf(Path::isAbsolute)
        if (tool.name == CREATE_WORKBOOK_TOOL) {
            val target = (requestedPath ?: Path.of(workbookPath)).toAbsolutePath().normalize()
            if (Files.exists(target)) {
                throw AgentException("Excel workbook already exists and will not be overwritten: $target")
            }
            return arguments.toMutableMap().apply {
                put("filepath", target.toString())
                put("overwrite", false)
            }
        }

        if (tool.name !in READ_ONLY_EXCEL_TOOLS) {
            val target = requestedPath?.toAbsolutePath()?.normalize()
                ?: throw AgentException("Excel tool '${tool.name}' requires an absolute filepath.")
            if (target !in workbooksCreatedInThisRun) {
                throw AgentException(
                    "Refusing to modify an existing workbook that was not created in this run: $target",
                )
            }
        }
        return arguments
    }

    private fun createUniqueWorkbookPath(): String {
        val directory = outputDirectory.toAbsolutePath().normalize()
        Files.createDirectories(directory)
        var candidate: Path
        do {
            val timestamp = FILE_TIMESTAMP.format(Instant.now())
            val suffix = UUID.randomUUID().toString().take(8)
            candidate = directory.resolve("news-and-rates-$timestamp-$suffix.xlsx")
        } while (Files.exists(candidate))
        return candidate.toString()
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
        const val MAX_AGENT_TURNS = 12
        const val MAX_TOOL_CALLS = 24
        val FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC)
        val READ_ONLY_EXCEL_TOOLS = setOf(
            "describe_workbook",
            "filter_rows",
            "find_in_workbook",
            "get_data_validation_info",
            "get_merged_cells",
            "get_sheet_schema",
            "get_workbook_metadata",
            "list_charts",
            "read_data_from_excel",
            "validate_excel_range",
            "validate_formula_syntax",
        )
        const val SYSTEM_PROMPT =
            "Ты агент с доступом к инструментам нескольких MCP-серверов. " +
                "Сам решай, нужны ли инструменты для запроса: можно не вызывать ни одного, вызвать один или " +
                "построить многошаговый flow по результатам предыдущих вызовов. " +
                "Для новостей используй News MCP, для курсов и конвертации валют — Frankfurter MCP, " +
                "для создания, чтения и изменения .xlsx — Excel MCP. Не вызывай нерелевантные инструменты. " +
                "Если пользователь просит создать Excel и не указал другой новый путь, используй абсолютный путь " +
                "из системного сообщения AVAILABLE_EXCEL_OUTPUT_PATH. Никогда не перезаписывай существующую книгу: " +
                "для create_workbook передавай overwrite=false. После tools анализируй результаты и дай ясный ответ."
    }
}
