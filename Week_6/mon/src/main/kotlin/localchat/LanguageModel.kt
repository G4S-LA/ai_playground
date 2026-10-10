package localchat

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ModelUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface ChatModel {
    val modelName: String
    val endpoint: String
    suspend fun complete(messages: List<ChatMessage>): String
    suspend fun status(): ModelStatus
}

class OllamaChatModel(
    override val endpoint: String,
    override val modelName: String,
    private val temperature: Double,
    timeoutSeconds: Long,
    private val gson: Gson = Gson(),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) : ChatModel {
    private val timeout = Duration.ofSeconds(timeoutSeconds)

    override suspend fun complete(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        require(messages.isNotEmpty()) { "История запроса не должна быть пустой." }
        val body = gson.toJson(OllamaChatRequest(modelName, messages, options = OllamaOptions(temperature)))
        val request = HttpRequest.newBuilder(URI.create("$endpoint/api/chat"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = send(request)
        if (response.statusCode() !in 200..299) {
            val details = parseError(response.body())
            throw ModelUnavailableException("Ollama вернула HTTP ${response.statusCode()}: $details")
        }
        val payload = runCatching { gson.fromJson(response.body(), OllamaChatResponse::class.java) }
            .getOrElse { throw ModelUnavailableException("Ollama вернула некорректный JSON.", it) }
        val answer = payload.message?.content?.trim().orEmpty()
        if (answer.isEmpty()) throw ModelUnavailableException("Локальная модель вернула пустой ответ.")
        answer
    }

    override suspend fun status(): ModelStatus = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create("$endpoint/api/tags"))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build()
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return@withContext unavailable("HTTP ${response.statusCode()}: ${parseError(response.body())}")
            }
            val payload = gson.fromJson(response.body(), OllamaTagsResponse::class.java)
            val models = payload.models.mapNotNull { it.name ?: it.model }.distinct().sorted()
            ModelStatus(
                endpoint = endpoint,
                model = modelName,
                reachable = true,
                installed = models.any { sameModel(it, modelName) },
                availableModels = models,
            )
        } catch (error: Exception) {
            unavailable(connectionMessage(error))
        }
    }

    private fun send(request: HttpRequest): HttpResponse<String> = try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (error: Exception) {
        throw ModelUnavailableException(connectionMessage(error), error)
    }

    private fun unavailable(message: String) = ModelStatus(
        endpoint = endpoint,
        model = modelName,
        reachable = false,
        installed = false,
        error = message,
    )

    private fun parseError(body: String): String = runCatching {
        gson.fromJson(body, OllamaError::class.java).error?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: body.trim().take(500).ifBlank { "нет описания" }

    private fun connectionMessage(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val details = root.message?.takeIf { it.isNotBlank() } ?: root::class.simpleName
        return "Не удалось подключиться к Ollama по адресу $endpoint. " +
            "Запустите `ollama serve`. Причина: $details"
    }

    private fun sameModel(installed: String, requested: String): Boolean =
        installed == requested || installed.removeSuffix(":latest") == requested.removeSuffix(":latest")

    private data class OllamaChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val stream: Boolean = false,
        val options: OllamaOptions,
    )

    private data class OllamaOptions(val temperature: Double)
    private data class OllamaChatResponse(val message: ChatMessage? = null)
    private data class OllamaTagsResponse(val models: List<OllamaModel> = emptyList())
    private data class OllamaModel(val name: String? = null, val model: String? = null)
    private data class OllamaError(val error: String? = null)
}
