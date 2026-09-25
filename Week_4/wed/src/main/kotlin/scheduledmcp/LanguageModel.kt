package scheduledmcp

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
            throw AgentException("Не удалось вызвать модель: ${error.message}", error)
        }
        if (response.statusCode() !in 200..299) {
            throw AgentException("Модель вернула HTTP ${response.statusCode()}: ${response.body().take(500)}")
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
            throw AgentException("Не удалось разобрать ответ модели.", error)
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
                    addProperty("description", tool.description)
                    add("parameters", gson.fromJson(tool.inputSchemaJson, JsonObject::class.java))
                })
            })
        }
    }
}

internal class DemoNewsLanguageModel(
    private val gson: Gson = Gson(),
) : LanguageModel {
    override fun complete(messages: List<AgentMessage>, tools: List<AgentTool>): ModelTurn {
        val last = messages.last()
        if (last.role == "tool") {
            val json = gson.fromJson(last.content, JsonObject::class.java)
            if (json.has("stories")) {
                val stories = json.getAsJsonArray("stories")
                val report = buildString {
                    appendLine("Топ новостей Hacker News за сутки")
                    stories.forEachIndexed { index, value ->
                        val story = value.asJsonObject
                        appendLine()
                        append("${index + 1}. ${story.get("title").asString}")
                        append(" — ${story.get("score").asInt} points")
                        appendLine()
                        append(story.get("url").asString)
                    }
                }.trim()
                return ModelTurn(report)
            }
            return ModelTurn("Расписание сохранено через MCP: ${json.get("nextRunAt")?.asString}")
        }

        val scheduleTool = tools.firstOrNull { it.name == SCHEDULE_REPORT_TOOL }
        if (scheduleTool != null) {
            val runAt = Regex("runAt=([^\\s]+)").find(last.content)?.groupValues?.get(1)
            val args = JsonObject().apply {
                addProperty("title", "Ежедневный топ новостей")
                addProperty("instruction", "Собери топ Hacker News за последние 24 часа и подготовь краткий отчёт.")
                if (runAt != null) addProperty("runAt", runAt) else addProperty("cron", "0 18 * * *")
                addProperty("topLimit", 5)
            }
            return ModelTurn(
                "",
                listOf(ModelToolCall("demo-schedule-${System.nanoTime()}", scheduleTool.name, gson.toJson(args))),
            )
        }

        val newsTool = tools.firstOrNull { it.name == GET_TOP_NEWS_TOOL }
            ?: return ModelTurn("Нужный MCP-инструмент недоступен.")
        val limit = Regex("topLimit=(\\d+)").find(last.content)?.groupValues?.get(1)?.toInt() ?: 5
        return ModelTurn(
            "",
            listOf(
                ModelToolCall(
                    "demo-news-${System.nanoTime()}",
                    newsTool.name,
                    "{\"windowHours\":24,\"limit\":$limit}",
                ),
            ),
        )
    }
}
