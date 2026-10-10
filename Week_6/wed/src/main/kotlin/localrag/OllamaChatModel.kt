package localrag

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LocalModelException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface LocalChatModel {
    val description: String
    suspend fun generate(systemPrompt: String, userPrompt: String): String
    suspend fun status(): OllamaStatus
}

class OllamaChatModel(
    private val baseUrl: String,
    private val model: String,
    private val temperature: Double,
    timeoutSeconds: Long,
    private val gson: Gson = Gson(),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) : LocalChatModel {
    private val timeout = Duration.ofSeconds(timeoutSeconds)
    override val description: String = "Ollama · $model"

    override suspend fun generate(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        val payload = ChatRequest(
            model = model,
            messages = listOf(Message("system", systemPrompt), Message("user", userPrompt)),
            options = Options(temperature),
        )
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/chat"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()
        val response = send(request)
        if (response.statusCode() !in 200..299) {
            throw LocalModelException("Ollama вернула HTTP ${response.statusCode()}: ${errorText(response.body())}")
        }
        val body = runCatching { gson.fromJson(response.body(), ChatResponse::class.java) }
            .getOrElse { throw LocalModelException("Ollama вернула некорректный JSON.", it) }
        body.message?.content?.trim().orEmpty()
            .ifEmpty { throw LocalModelException("Локальная модель вернула пустой ответ.") }
    }

    override suspend fun status(): OllamaStatus = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/tags"))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build()
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return@withContext OllamaStatus(baseUrl, false, error = "HTTP ${response.statusCode()}")
            }
            val body = gson.fromJson(response.body(), TagsResponse::class.java)
            OllamaStatus(
                endpoint = baseUrl,
                reachable = true,
                availableModels = body.models.mapNotNull { it.name ?: it.model }.distinct().sorted(),
            )
        } catch (error: Exception) {
            OllamaStatus(baseUrl, false, error = connectionMessage(error))
        }
    }

    private fun send(request: HttpRequest): HttpResponse<String> = try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (error: Exception) {
        throw LocalModelException(connectionMessage(error), error)
    }

    private fun connectionMessage(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val details = root.message?.takeIf { it.isNotBlank() } ?: root::class.simpleName
        return "Не удалось подключиться к локальной Ollama по адресу $baseUrl. " +
            "Запустите `ollama serve`. Причина: $details"
    }

    private fun errorText(body: String): String = runCatching {
        gson.fromJson(body, ErrorResponse::class.java).error?.takeIf(String::isNotBlank)
    }.getOrNull() ?: body.trim().take(500).ifBlank { "нет описания" }

    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val stream: Boolean = false,
        val options: Options,
    )
    private data class Options(val temperature: Double)
    private data class Message(val role: String = "", val content: String = "")
    private data class ChatResponse(val message: Message? = null)
    private data class TagsResponse(val models: List<ModelTag> = emptyList())
    private data class ModelTag(val name: String? = null, val model: String? = null)
    private data class ErrorResponse(val error: String? = null)
}
