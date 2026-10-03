package ragagent

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface ChatLanguageModel {
    val description: String
    suspend fun generate(systemPrompt: String, userPrompt: String): String
}

class OpenAiCompatibleChatLanguageModel(
    private val apiKey: String,
    private val apiUrl: String,
    private val model: String,
    private val gson: Gson = Gson(),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : ChatLanguageModel {
    init {
        require(apiKey.isNotBlank()) { "LLM API key не должен быть пустым." }
        require(apiUrl.startsWith("https://") || apiUrl.startsWith("http://")) {
            "LLM_API_URL должен начинаться с http:// или https://."
        }
        require(model.isNotBlank()) { "LLM_MODEL не должен быть пустым." }
    }

    override val description: String = "API · $model"

    override suspend fun generate(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        val payload = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userPrompt),
            ),
        )
        val request = HttpRequest.newBuilder(URI.create(apiUrl))
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException("Не удалось подключиться к LLM API по адресу $apiUrl.", error)
        }
        check(response.statusCode() in 200..299) {
            "LLM API вернул HTTP ${response.statusCode()}: ${response.body().take(500)}"
        }
        val body = try {
            gson.fromJson(response.body(), ChatCompletionResponse::class.java)
        } catch (error: Exception) {
            throw IllegalStateException("LLM API вернул некорректный JSON.", error)
        }
        body.choices.firstOrNull()?.message?.content?.trim().orEmpty()
            .ifEmpty { error("LLM API вернул пустой ответ.") }
    }

    private data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = 0.1,
    )

    private data class ChatMessage(val role: String = "", val content: String = "")
    private data class ChatChoice(val message: ChatMessage = ChatMessage())
    private data class ChatCompletionResponse(val choices: List<ChatChoice> = emptyList())
}

class OllamaChatLanguageModel(
    private val baseUrl: String,
    private val model: String,
    private val gson: Gson = Gson(),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : ChatLanguageModel {
    override val description: String = "Ollama · $model"

    override suspend fun generate(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        val payload = OllamaChatRequest(
            model = model,
            messages = listOf(
                OllamaMessage("system", systemPrompt),
                OllamaMessage("user", userPrompt),
            ),
        )
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/chat"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException(
                "Не удалось подключиться к Ollama по адресу $baseUrl. Запустите Ollama и проверьте модели.",
                error,
            )
        }
        check(response.statusCode() in 200..299) {
            "Ollama вернула HTTP ${response.statusCode()}: ${response.body().take(500)}"
        }
        val body = gson.fromJson(response.body(), OllamaChatResponse::class.java)
        body.message.content.trim().ifEmpty { error("Ollama вернула пустой ответ.") }
    }

    private data class OllamaChatRequest(
        val model: String,
        val messages: List<OllamaMessage>,
        val stream: Boolean = false,
        val think: Boolean = false,
        val options: Map<String, Any> = mapOf("temperature" to 0.1, "seed" to 42),
    )

    private data class OllamaMessage(val role: String = "", val content: String = "")
    private data class OllamaChatResponse(val message: OllamaMessage = OllamaMessage())
}

class DemoChatLanguageModel : ChatLanguageModel {
    override val description: String = "Demo · deterministic"

    override suspend fun generate(systemPrompt: String, userPrompt: String): String =
        if ("[S1]" in userPrompt) {
            "Демонстрационный ответ построен по найденному фрагменту базы знаний [S1]."
        } else {
            "Демонстрационный ответ без обращения к базе знаний."
        }
}
