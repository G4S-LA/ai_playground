package toolpipeline

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val SEARCH_TOOL = "search"
internal const val SUMMARIZE_TOOL = "summarize"
internal const val SAVE_TOOL = "saveToFile"

internal suspend fun runPipelineMcpServer(config: AppConfig) {
    val gson = Gson()
    val searchService = LocalSearchService()
    val summarizer = ExtractiveSummarizer()
    val fileStore = SafeFileStore(config.outputDirectory)
    val server = Server(
        serverInfo = Implementation(name = "tool-pipeline-mcp", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    )

    server.addTool(
        name = SEARCH_TOOL,
        description = "Первый этап. Ищет исходные данные и возвращает SearchResult для summarize.",
        inputSchema = schema(
            required = listOf("query"),
            "query" to stringProperty("Поисковый запрос"),
            "limit" to integerProperty("Максимум результатов", 1, 10, 3),
        ),
    ) { request ->
        result {
            gson.toJson(
                searchService.search(
                    query = request.requiredString("query"),
                    limit = request.int("limit") ?: 3,
                ),
            )
        }
    }

    server.addTool(
        name = SUMMARIZE_TOOL,
        description = "Второй этап. Принимает полный SearchResult предыдущего search без изменений и создаёт SummaryResult.",
        inputSchema = schema(
            required = listOf("searchResult"),
            "searchResult" to searchResultProperty(),
            "maxSentences" to integerProperty("Максимум пунктов в сводке", 1, 10, 3),
        ),
    ) { request ->
        result {
            val searchResult = gson.fromJson(request.requiredObject("searchResult").toString(), SearchResult::class.java)
            gson.toJson(summarizer.summarize(searchResult, request.int("maxSentences") ?: 3))
        }
    }

    server.addTool(
        name = SAVE_TOOL,
        description = "Третий этап. Принимает полный SummaryResult предыдущего summarize и сохраняет его в файл.",
        inputSchema = schema(
            required = listOf("summary", "fileName"),
            "summary" to summaryResultProperty(),
            "fileName" to stringProperty("Имя выходного .md или .txt файла без пути"),
        ),
    ) { request ->
        result {
            val summary = gson.fromJson(request.requiredObject("summary").toString(), SummaryResult::class.java)
            gson.toJson(fileStore.save(summary, request.requiredString("fileName")))
        }
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

private fun searchResultProperty(): JsonElement = buildJsonObject {
    put("type", "object")
    put("description", "JSON-результат search; передать целиком без пересборки")
    put("properties", buildJsonObject {
        put("query", stringProperty("Исходный запрос"))
        put("items", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("id", stringProperty("Идентификатор источника"))
                    put("title", stringProperty("Заголовок"))
                    put("url", stringProperty("Ссылка"))
                    put("content", stringProperty("Текст"))
                })
                put("required", strings("id", "title", "url", "content"))
            })
        })
    })
    put("required", strings("query", "items"))
}

private fun summaryResultProperty(): JsonElement = buildJsonObject {
    put("type", "object")
    put("description", "JSON-результат summarize; передать целиком без пересборки")
    put("properties", buildJsonObject {
        put("sourceQuery", stringProperty("Запрос, из которого сделана сводка"))
        put("sourceCount", integerProperty("Количество найденных источников", 1, 10))
        put("summary", stringProperty("Готовая сводка"))
        put("sourceIds", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
        })
    })
    put("required", strings("sourceQuery", "sourceCount", "summary", "sourceIds"))
}

private fun strings(vararg values: String): JsonElement = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.requiredString(name: String): String =
    arguments?.get(name)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("$name is required")

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.int(name: String): Int? =
    arguments?.get(name)?.jsonPrimitive?.intOrNull

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.requiredObject(name: String): JsonElement =
    arguments?.get(name)?.takeIf { element -> element.toString().startsWith("{") }
        ?: error("$name must be an object")

private inline fun result(block: () -> String): CallToolResult = try {
    CallToolResult(content = listOf(TextContent(block())), isError = false)
} catch (error: Exception) {
    CallToolResult(
        content = listOf(TextContent(error.message ?: "MCP pipeline tool failed")),
        isError = true,
    )
}
