package scheduledmcp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant
import java.time.ZoneId

internal class NewsAgent(
    private val model: LanguageModel,
    private val gateway: ToolGateway,
    private val defaultTimeZone: String,
    private val gson: Gson = Gson(),
) {
    suspend fun schedule(request: String): String = scheduleWithDetails(request).answer

    suspend fun scheduleWithDetails(
        request: String,
        conversation: List<AgentMessage> = emptyList(),
    ): ScheduledAnswer = scheduleInternal(request, null, conversation)

    suspend fun scheduleOnceNowForDemo(): String = scheduleInternal(
        request = "Собери топ новостей за сутки и отправь отчёт сейчас.",
        forcedRunAt = Instant.now().minusSeconds(1).toString(),
        conversation = emptyList(),
    ).answer

    suspend fun runDue(): List<ExecutedReport> = gateway.withSession {
        val tools = listTools()
        val newsTool = tools.singleOrNull { it.name == GET_TOP_NEWS_TOOL }
            ?: throw AgentException("MCP-инструмент $GET_TOP_NEWS_TOOL недоступен.")
        val dueJson = callTool(CLAIM_DUE_TOOL, emptyMap())
        val dueReports = gson.fromJson(dueJson, DueResponse::class.java).dueReports.orEmpty()
        dueReports.mapNotNull { due ->
            try {
                val messages = mutableListOf(
                    AgentMessage(
                        role = "system",
                        content = "Ты агент новостных отчётов. Обязательно вызови MCP-инструмент, " +
                            "затем подготовь краткий структурированный отчёт на русском языке со ссылками.",
                    ),
                    AgentMessage(
                        role = "user",
                        content = "[EXECUTE] topLimit=${due.schedule.topLimit}\n${due.schedule.instruction}",
                    ),
                )
                val roundTrip = callModelTool(messages, newsTool)
                val newsBatch = gson.fromJson(roundTrip.toolResult, NewsBatch::class.java)
                callTool(
                    COMPLETE_REPORT_TOOL,
                    mapOf(
                        "runId" to due.run.id,
                        "report" to roundTrip.answer,
                        "storiesCount" to newsBatch.count,
                    ),
                )
                ExecutedReport(due, roundTrip.answer, newsBatch.count)
            } catch (error: Exception) {
                runCatching {
                    callTool(
                        FAIL_REPORT_TOOL,
                        mapOf("runId" to due.run.id, "error" to (error.message ?: "Неизвестная ошибка")),
                    )
                }
                System.err.println("Не удалось выполнить '${due.schedule.title}': ${error.message}")
                null
            }
        }
    }

    suspend fun listSchedules(): String = gateway.withSession {
        callTool(LIST_SCHEDULES_TOOL, emptyMap())
    }

    suspend fun schedules(): List<NewsSchedule> = gson.fromJson(
        listSchedules(),
        SchedulesResponse::class.java,
    ).schedules.orEmpty()

    suspend fun history(): String = gateway.withSession {
        callTool(REPORT_HISTORY_TOOL, mapOf("limit" to 20))
    }

    suspend fun reportHistory(): ReportHistory = gson.fromJson(history(), ReportHistory::class.java)

    suspend fun clearData(): ClearNewsDataResult = gateway.withSession {
        gson.fromJson(callTool(CLEAR_NEWS_DATA_TOOL, emptyMap()), ClearNewsDataResult::class.java)
    }

    private suspend fun scheduleInternal(
        request: String,
        forcedRunAt: String?,
        conversation: List<AgentMessage>,
    ): ScheduledAnswer = gateway.withSession {
        val scheduleTool = listTools().singleOrNull { it.name == SCHEDULE_REPORT_TOOL }
            ?: throw AgentException("MCP-инструмент $SCHEDULE_REPORT_TOOL недоступен.")
        val marker = forcedRunAt?.let { "[SCHEDULE] runAt=$it" } ?: "[SCHEDULE]"
        val now = Instant.now()
        val localNow = now.atZone(ZoneId.of(defaultTimeZone))
        val messages = mutableListOf(
            AgentMessage(
                role = "system",
                content = "Ты планировщик новостных отчётов. Для ежедневного времени используй cron " +
                    "'минута час * * *'. Часовой пояс по умолчанию: $defaultTimeZone. " +
                    "Текущее время: $localNow ($now UTC). Относительное время вроде 'через 30 секунд' " +
                    "преобразуй в точный runAt ISO-8601. " +
                    "Обязательно вызови MCP-инструмент сохранения расписания.",
            ),
        )
        messages += conversation.takeLast(12).filter { it.role == "user" || it.role == "assistant" }
        messages += AgentMessage(role = "user", content = "$marker\n$request")
        val roundTrip = callModelTool(messages, scheduleTool) { arguments ->
            arguments.putIfAbsent("timeZone", defaultTimeZone)
            arguments.putIfAbsent("instruction", request)
            arguments.putIfAbsent("title", request.take(80))
            if (forcedRunAt != null) {
                arguments.remove("cron")
                arguments["runAt"] = forcedRunAt
            }
        }
        ScheduledAnswer(
            answer = roundTrip.answer,
            schedule = gson.fromJson(roundTrip.toolResult, NewsSchedule::class.java),
        )
    }

    private suspend fun ToolSession.callModelTool(
        messages: MutableList<AgentMessage>,
        tool: AgentTool,
        enrichArguments: (MutableMap<String, Any?>) -> Unit = {},
    ): ToolRoundTrip {
        val decision = model.complete(messages, listOf(tool))
        val call = decision.toolCalls.singleOrNull()
            ?: throw AgentException("Модель не запросила ровно один MCP-инструмент.")
        require(call.name == tool.name) { "Модель запросила неожиданный инструмент '${call.name}'." }
        messages += AgentMessage(role = "assistant", content = decision.content, toolCalls = listOf(call))
        val arguments = parseArguments(call.argumentsJson).toMutableMap()
        enrichArguments(arguments)
        val toolResult = callTool(call.name, arguments)
        messages += AgentMessage(role = "tool", content = toolResult, toolCallId = call.id)
        val final = model.complete(messages, emptyList())
        require(final.toolCalls.isEmpty()) { "Модель запросила лишний второй инструмент." }
        require(final.content.isNotBlank()) { "Модель вернула пустой отчёт." }
        return ToolRoundTrip(final.content.trim(), toolResult)
    }

    private fun parseArguments(json: String): Map<String, Any?> {
        val root = try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (error: Exception) {
            throw AgentException("Модель вернула некорректные аргументы инструмента.", error)
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

    private data class ToolRoundTrip(val answer: String, val toolResult: String)
    private data class DueResponse(val dueReports: List<DueReport>? = emptyList())
    private data class SchedulesResponse(val schedules: List<NewsSchedule>? = emptyList())
}
