package multimcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal interface LanguageModel {
    fun complete(messages: List<AgentMessage>, tools: List<AgentTool>): ModelTurn
}

internal class OpenAiCompatibleModel(
    private val config: AppConfig,
    private val gson: Gson = Gson(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
        .build(),
) : LanguageModel {
    override fun complete(messages: List<AgentMessage>, tools: List<AgentTool>): ModelTurn {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("temperature", config.temperature)
            add("messages", messagesToJson(messages))
            if (tools.isNotEmpty()) {
                add("tools", toolsToJson(tools))
                addProperty("tool_choice", "auto")
            }
        }
        val request = HttpRequest.newBuilder(URI.create(config.apiUrl))
            .timeout(Duration.ofSeconds(config.timeoutSeconds))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw AgentException("Cannot call the language model: ${error.message}", error)
        }
        if (response.statusCode() !in 200..299) {
            throw AgentException("Model returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
        }
        return try {
            val message = gson.fromJson(response.body(), JsonObject::class.java)
                .getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
            val content = message.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val calls = message.getAsJsonArray("tool_calls")?.map { value ->
                val call = value.asJsonObject
                val function = call.getAsJsonObject("function")
                ModelToolCall(
                    id = call.get("id").asString,
                    name = function.get("name").asString,
                    argumentsJson = function.get("arguments").asString,
                )
            }.orEmpty()
            ModelTurn(content.trim(), calls)
        } catch (error: Exception) {
            throw AgentException("Cannot parse the model response.", error)
        }
    }

    private fun messagesToJson(messages: List<AgentMessage>): JsonArray = JsonArray().also { array ->
        messages.forEach { message ->
            array.add(JsonObject().apply {
                addProperty("role", message.role)
                if (message.role == "assistant" && message.toolCalls.isNotEmpty()) {
                    add("content", JsonNull.INSTANCE)
                    add("tool_calls", JsonArray().also { calls ->
                        message.toolCalls.forEach { call ->
                            calls.add(JsonObject().apply {
                                addProperty("id", call.id)
                                addProperty("type", "function")
                                add("function", JsonObject().apply {
                                    addProperty("name", call.name)
                                    addProperty("arguments", call.argumentsJson)
                                })
                            })
                        }
                    })
                } else {
                    addProperty("content", message.content)
                }
                message.toolCallId?.let { addProperty("tool_call_id", it) }
            })
        }
    }

    private fun toolsToJson(tools: List<AgentTool>): JsonArray = JsonArray().also { array ->
        tools.forEach { tool ->
            array.add(JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", tool.name)
                    addProperty(
                        "description",
                        "MCP server '${tool.serverName}' (${tool.serverId}). ${tool.description}",
                    )
                    add("parameters", gson.fromJson(tool.inputSchemaJson, JsonObject::class.java))
                })
            })
        }
    }
}

internal class DemoMultiMcpLanguageModel(
    private val gson: Gson = Gson(),
) : LanguageModel {
    override fun complete(messages: List<AgentMessage>, tools: List<AgentTool>): ModelTurn {
        val results = messages.filter { it.role == "tool" }.map(AgentMessage::content)
        val filePath = workbookPath(messages)
        val userRequest = messages.firstOrNull { it.role == "user" }?.content.orEmpty()
        val request = userRequest.lowercase()
        val wantsNews = request.containsAny("новост", "news", "hacker")
        val wantsRates = request.containsAny(
            "курс", "валют", "currency", "exchange", "конверт", "usd", "eur", "gbp", "jpy", "rub",
        )
        val wantsExcel = request.containsAny("excel", "xlsx", "эксел", "таблиц", "сохран", "workbook")
        val plan = buildList {
            if (wantsNews) add(GET_TOP_NEWS_TOOL)
            if (wantsRates) add(GET_RATES_TOOL)
            if (wantsExcel) {
                add(CREATE_WORKBOOK_TOOL)
                if (wantsNews || wantsRates) {
                    add(WRITE_EXCEL_TOOL)
                    add(CREATE_TABLE_TOOL)
                }
                add(INSPECT_WORKBOOK_TOOL)
            }
        }
        val resultByTool = plan.take(results.size).zip(results).toMap()
        val nextTool = plan.getOrNull(results.size)
            ?: return finalAnswer(plan, results, filePath)

        return when (nextTool) {
            GET_TOP_NEWS_TOOL -> call(tools, nextTool, results.size + 1, JsonObject().apply {
                addProperty("windowHours", 24)
                addProperty("limit", 5)
            })
            GET_RATES_TOOL -> call(tools, nextTool, results.size + 1, currencyArguments(userRequest))
            CREATE_WORKBOOK_TOOL -> call(tools, nextTool, results.size + 1, JsonObject().apply {
                addProperty("filepath", filePath)
                addProperty("overwrite", false)
            })
            WRITE_EXCEL_TOOL -> call(
                tools,
                nextTool,
                results.size + 1,
                buildWriteArguments(
                    filePath,
                    resultByTool[GET_TOP_NEWS_TOOL],
                    resultByTool[GET_RATES_TOOL],
                ),
            )
            CREATE_TABLE_TOOL -> call(tools, nextTool, results.size + 1, JsonObject().apply {
                addProperty("filepath", filePath)
                addProperty("sheet_name", "Sheet1")
                addProperty(
                    "data_range",
                    "A1:F${combinedRows(
                        resultByTool[GET_TOP_NEWS_TOOL],
                        resultByTool[GET_RATES_TOOL],
                    ).size() + 1}",
                )
                addProperty("table_name", "NewsAndRates")
                addProperty("table_style", "TableStyleMedium9")
            })
            INSPECT_WORKBOOK_TOOL -> call(tools, nextTool, results.size + 1, JsonObject().apply {
                addProperty("filepath", filePath)
                addProperty("include_ranges", true)
            })
            else -> throw AgentException("Demo model cannot call tool '$nextTool'.")
        }
    }

    private fun finalAnswer(plan: List<String>, results: List<String>, filePath: String): ModelTurn = when {
        plan.isEmpty() -> ModelTurn("Для этого запроса demo-модели MCP-инструменты не нужны.")
        CREATE_WORKBOOK_TOOL in plan -> ModelTurn(
            "Запрос выполнен, новая Excel-книга сохранена в $filePath. " +
                "Использовано MCP-вызовов: ${plan.size}.",
        )
        else -> ModelTurn(
            "Запрос выполнен через ${plan.joinToString()}. Результат:\n${results.joinToString("\n")}",
        )
    }

    private fun workbookPath(messages: List<AgentMessage>): String = messages
        .asSequence()
        .filter { it.role == "system" }
        .map(AgentMessage::content)
        .firstOrNull { it.startsWith(WORKBOOK_CONTEXT_PREFIX) }
        ?.removePrefix(WORKBOOK_CONTEXT_PREFIX)
        ?.takeIf(String::isNotBlank)
        ?: throw AgentException("Demo model did not receive an Excel output path.")

    private fun buildWriteArguments(filePath: String, newsJson: String?, ratesJson: String?): JsonObject {
        val rows = combinedRows(newsJson, ratesJson)
        require(rows.size() > 0) { "News and currency MCPs returned no rows for Excel." }
        return JsonObject().apply {
            addProperty("filepath", filePath)
            addProperty("sheet_name", "Sheet1")
            add("data", rows)
            addProperty("start_cell", "A1")
        }
    }

    private fun combinedRows(newsJson: String?, ratesJson: String?): JsonArray {
        val rows = JsonArray()
        newsJson?.let { json ->
            val news = gson.fromJson(json, JsonObject::class.java)
            (news.getAsJsonArray("stories") ?: JsonArray()).forEach { value ->
                val story = value.asJsonObject
                rows.add(JsonObject().apply {
                    addProperty("Category", "News")
                    addProperty("Name", story.get("title").asString)
                    addProperty("Value", story.get("score").asInt)
                    addProperty("Details", "${story.get("comments").asInt} comments")
                    addProperty("URL", story.get("url").asString)
                    addProperty("Date", story.get("publishedAt").asString)
                })
            }
        }
        ratesJson?.let { json ->
            gson.fromJson(json, JsonArray::class.java).forEach { value ->
                val rate = value.asJsonObject
                rows.add(JsonObject().apply {
                    addProperty("Category", "FX")
                    addProperty("Name", "${rate.get("base").asString}/${rate.get("quote").asString}")
                    addProperty("Value", rate.get("rate").asDouble)
                    addProperty("Details", "Blended reference rate")
                    addProperty("URL", "https://frankfurter.dev")
                    addProperty("Date", rate.get("date").asString)
                })
            }
        }
        return rows
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any(::contains)

    private fun currencyArguments(request: String): JsonObject {
        val requestedCodes = Regex("\\b[A-Za-z]{3}\\b")
            .findAll(request)
            .map { it.value.uppercase() }
            .filter { it in DEMO_CURRENCIES }
            .distinct()
            .toList()
        val base = requestedCodes.firstOrNull() ?: "USD"
        val quotes = requestedCodes.drop(1).ifEmpty { listOf("EUR", "GBP", "JPY") }
        return JsonObject().apply {
            addProperty("base", base)
            add("quotes", JsonArray().apply { quotes.forEach(::add) })
        }
    }

    private fun call(
        tools: List<AgentTool>,
        expectedName: String,
        callNumber: Int,
        arguments: JsonObject,
    ): ModelTurn {
        val tool = tools.singleOrNull { it.name == expectedName }
            ?: throw AgentException("Tool '$expectedName' is unavailable.")
        return ModelTurn(
            content = "",
            toolCalls = listOf(ModelToolCall("demo-call-$callNumber", tool.name, gson.toJson(arguments))),
        )
    }

    private companion object {
        val DEMO_CURRENCIES = setOf(
            "AUD", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF", "IDR",
            "ILS", "INR", "ISK", "JPY", "KRW", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "RON",
            "RUB", "SEK", "SGD", "THB", "TRY", "USD", "ZAR",
        )
    }
}
