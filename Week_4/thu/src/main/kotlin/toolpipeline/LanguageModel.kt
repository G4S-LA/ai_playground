package toolpipeline

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
            throw PipelineException("Cannot call the language model: ${error.message}", error)
        }
        if (response.statusCode() !in 200..299) {
            throw PipelineException("Model returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
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
            throw PipelineException("Cannot parse the model response.", error)
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

internal class DemoPipelineLanguageModel(
    private val query: String = "MCP tool schemas and reliable pipelines",
    private val fileName: String = "pipeline-report.md",
    private val gson: Gson = Gson(),
) : LanguageModel {
    private var callNumber = 0

    override fun complete(messages: List<AgentMessage>, tools: List<AgentTool>): ModelTurn {
        val last = messages.last()
        if (last.role != "tool") {
            return call(tools, SEARCH_TOOL, JsonObject().apply {
                addProperty("query", query)
                addProperty("limit", 3)
            })
        }

        val previousResult = gson.fromJson(last.content, JsonObject::class.java)
        return when {
            previousResult.has("items") -> call(tools, SUMMARIZE_TOOL, JsonObject().apply {
                add("searchResult", previousResult)
                addProperty("maxSentences", 3)
            })
            previousResult.has("summary") -> call(tools, SAVE_TOOL, JsonObject().apply {
                add("summary", previousResult)
                addProperty("fileName", fileName)
            })
            previousResult.has("path") -> ModelTurn(
                "Пайплайн завершён: результат сохранён в ${previousResult.get("path").asString}",
            )
            else -> throw PipelineException("Demo model received an unknown tool result: $previousResult")
        }
    }

    private fun call(tools: List<AgentTool>, expectedName: String, arguments: JsonObject): ModelTurn {
        val tool = tools.singleOrNull { it.name == expectedName }
            ?: throw PipelineException("Tool '$expectedName' is unavailable.")
        callNumber += 1
        return ModelTurn(
            content = "",
            toolCalls = listOf(
                ModelToolCall("demo-call-$callNumber", tool.name, gson.toJson(arguments)),
            ),
        )
    }
}
