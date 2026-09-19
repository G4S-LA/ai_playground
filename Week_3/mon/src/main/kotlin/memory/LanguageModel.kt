package memory

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class PromptMessage(val role: String, val content: String)

interface LanguageModel {
    fun complete(messages: List<PromptMessage>): String
}

class OpenAiCompatibleModel(
    private val config: AppConfig,
    private val gson: Gson = Gson(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
        .build(),
) : LanguageModel {
    override fun complete(messages: List<PromptMessage>): String {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("temperature", config.temperature)
            add("messages", JsonArray().also { array ->
                messages.forEach { message ->
                    array.add(JsonObject().apply {
                        addProperty("role", message.role)
                        addProperty("content", message.content)
                    })
                }
            })
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
            throw AgentException("Не удалось обратиться к модели: ${error.message}", error)
        }
        if (response.statusCode() !in 200..299) {
            throw AgentException(
                "Модель вернула HTTP ${response.statusCode()}: ${response.body().take(500)}"
            )
        }
        return try {
            val root = gson.fromJson(response.body(), JsonObject::class.java)
            root.getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message")
                .get("content")
                .asString
                .trim()
        } catch (error: Exception) {
            throw AgentException("Не удалось прочитать ответ модели.", error)
        }
    }
}

class DemoLanguageModel : LanguageModel {
    override fun complete(messages: List<PromptMessage>): String {
        val query = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val longCount = messages.firstOrNull { it.content.startsWith("[LONG_TERM_MEMORY]") }
            ?.content?.lineSequence()?.count { it.startsWith("- ") } ?: 0
        val workingCount = messages.firstOrNull { it.content.startsWith("[WORKING_MEMORY]") }
            ?.content?.lineSequence()?.count { it.startsWith("- ") } ?: 0
        return "Демо-ответ на «${query.take(120)}». " +
            "В prompt передано записей: рабочая память — $workingCount, долговременная — $longCount."
    }
}
