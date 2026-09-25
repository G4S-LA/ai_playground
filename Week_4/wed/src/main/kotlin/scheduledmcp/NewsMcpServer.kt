package scheduledmcp

import com.google.gson.Gson
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

internal const val SCHEDULE_REPORT_TOOL = "schedule_news_report"
internal const val LIST_SCHEDULES_TOOL = "list_news_schedules"
internal const val CLAIM_DUE_TOOL = "claim_due_news_reports"
internal const val GET_TOP_NEWS_TOOL = "get_top_news"
internal const val COMPLETE_REPORT_TOOL = "complete_news_report"
internal const val FAIL_REPORT_TOOL = "fail_news_report"
internal const val REPORT_HISTORY_TOOL = "get_news_report_history"
internal const val CLEAR_NEWS_DATA_TOOL = "clear_news_data"

internal suspend fun runNewsMcpServer(config: AppConfig) {
    val store = JsonReportStore(config.dataFile)
    val news = HackerNewsSource(config.newsApiBaseUrl, config.newsCandidateLimit)
    val gson = Gson()
    val server = Server(
        serverInfo = Implementation(name = "scheduled-news-mcp", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    )

    server.addTool(
        name = SCHEDULE_REPORT_TOOL,
        description = "Сохраняет ежедневный cron-отчёт или одноразовый отложенный отчёт о новостях.",
        inputSchema = schema(
            required = listOf("title", "instruction"),
            "title" to stringProperty("Короткое название задания"),
            "instruction" to stringProperty("Инструкция агенту для будущего запуска"),
            "cron" to stringProperty("Ежедневный cron: 'минута час * * *', например '0 18 * * *'"),
            "runAt" to stringProperty("Время одноразового запуска в ISO-8601; используется вместо cron"),
            "timeZone" to stringProperty("Часовой пояс, например Europe/Moscow"),
            "topLimit" to integerProperty("Количество новостей в отчёте", 1, 20, 10),
        ),
    ) { request ->
        result {
            gson.toJson(
                store.createSchedule(
                    title = request.requiredString("title"),
                    instruction = request.requiredString("instruction"),
                    cron = request.string("cron"),
                    runAt = request.string("runAt"),
                    timeZone = request.string("timeZone") ?: config.defaultTimeZone,
                    topLimit = request.int("topLimit") ?: 10,
                ),
            )
        }
    }

    server.addTool(
        name = LIST_SCHEDULES_TOOL,
        description = "Возвращает сохранённые расписания и время следующего запуска.",
        inputSchema = schema(),
    ) {
        result { gson.toJson(mapOf("schedules" to store.listSchedules())) }
    }

    server.addTool(
        name = CLAIM_DUE_TOOL,
        description = "Забирает готовые к запуску задания; вызывается агентом, которого запускает cron.",
        inputSchema = schema(
            required = emptyList(),
            "now" to stringProperty("Текущее время ISO-8601; обычно не передаётся"),
        ),
    ) { request ->
        result {
            val now = request.string("now")?.let(Instant::parse) ?: Instant.now()
            gson.toJson(mapOf("dueReports" to store.claimDue(now)))
        }
    }

    server.addTool(
        name = GET_TOP_NEWS_TOOL,
        description = "Собирает top Hacker News, оставляет публикации за заданный период и сортирует по score.",
        inputSchema = schema(
            required = emptyList(),
            "windowHours" to integerProperty("Глубина выборки в часах", 1, 168, 24),
            "limit" to integerProperty("Количество новостей", 1, 20, 10),
        ),
    ) { request ->
        result {
            gson.toJson(
                news.topStories(
                    windowHours = request.int("windowHours") ?: 24,
                    limit = request.int("limit") ?: 10,
                ),
            )
        }
    }

    server.addTool(
        name = COMPLETE_REPORT_TOOL,
        description = "Сохраняет сформированный агентом отчёт и завершает запуск успешно.",
        inputSchema = schema(
            required = listOf("runId", "report", "storiesCount"),
            "runId" to stringProperty("Идентификатор запуска из claim_due_news_reports"),
            "report" to stringProperty("Готовый текст отчёта"),
            "storiesCount" to integerProperty("Число новостей в отчёте", 0, 20),
        ),
    ) { request ->
        result {
            gson.toJson(
                store.completeRun(
                    runId = request.requiredString("runId"),
                    report = request.requiredString("report"),
                    storiesCount = request.int("storiesCount") ?: error("storiesCount обязателен"),
                ),
            )
        }
    }

    server.addTool(
        name = FAIL_REPORT_TOOL,
        description = "Сохраняет ошибку выполнения отчёта и завершает запуск с ошибкой.",
        inputSchema = schema(
            required = listOf("runId", "error"),
            "runId" to stringProperty("Идентификатор запуска"),
            "error" to stringProperty("Описание ошибки"),
        ),
    ) { request ->
        result {
            gson.toJson(store.failRun(request.requiredString("runId"), request.requiredString("error")))
        }
    }

    server.addTool(
        name = REPORT_HISTORY_TOOL,
        description = "Возвращает агрегированную статистику и историю сохранённых отчётов.",
        inputSchema = schema(
            required = emptyList(),
            "scheduleId" to stringProperty("Фильтр по идентификатору расписания"),
            "limit" to integerProperty("Количество последних запусков", 1, 100, 20),
        ),
    ) { request ->
        result {
            gson.toJson(store.history(request.string("scheduleId"), request.int("limit") ?: 20))
        }
    }

    server.addTool(
        name = CLEAR_NEWS_DATA_TOOL,
        description = "Удаляет все сохранённые расписания и всю историю запусков и отчётов.",
        inputSchema = schema(),
    ) {
        result { gson.toJson(store.clearAll()) }
    }

    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )
    val closed = CompletableDeferred<Unit>()
    try {
        val session = server.createSession(transport)
        session.onClose { closed.complete(Unit) }
        closed.await()
    } finally {
        server.close()
    }
}

private fun schema(
    required: List<String> = emptyList(),
    vararg properties: Pair<String, JsonElement>,
): ToolSchema = ToolSchema(
    properties = buildJsonObject { properties.forEach { (name, value) -> put(name, value) } },
    required = required,
)

private fun stringProperty(description: String): JsonElement = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun integerProperty(
    description: String,
    minimum: Int,
    maximum: Int,
    default: Int? = null,
): JsonElement = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", minimum)
    put("maximum", maximum)
    default?.let { put("default", it) }
}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.requiredString(name: String): String =
    string(name)?.takeIf(String::isNotBlank) ?: error("$name обязателен")

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.string(name: String): String? =
    arguments?.get(name)?.jsonPrimitive?.contentOrNull

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.int(name: String): Int? =
    arguments?.get(name)?.jsonPrimitive?.intOrNull

private inline fun result(block: () -> String): CallToolResult = try {
    CallToolResult(content = listOf(TextContent(block())), isError = false)
} catch (error: Exception) {
    CallToolResult(
        content = listOf(TextContent(error.message ?: "Ошибка scheduled-news MCP")),
        isError = true,
    )
}
