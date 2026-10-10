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

internal fun selectModel(configuredModel: String?, availableModels: List<String>): String? {
    val preferred = configuredModel?.let { requested ->
        availableModels.firstOrNull {
            it == requested || it.removeSuffix(":latest") == requested.removeSuffix(":latest")
        }
    }
    return preferred ?: availableModels.firstOrNull()
}

interface ChatModel {
    val endpoint: String
    suspend fun complete(messages: List<ChatMessage>): String
    suspend fun status(): ModelStatus
}

class OllamaChatModel(
    override val endpoint: String,
    private val configuredModel: String?,
    timeoutSeconds: Long,
    private val gson: Gson = Gson(),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) : ChatModel {
    private val timeout = Duration.ofSeconds(timeoutSeconds)

    @Volatile
    private var resolvedModel: String? = null

    override suspend fun complete(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        require(messages.isNotEmpty()) { "История запроса не должна быть пустой." }
        val model = resolvedModel ?: status().takeIf { it.installed }?.model
            ?: throw ModelUnavailableException(
                "В Ollama нет доступных моделей. Выполните `ollama list` и загрузите chat-модель.",
            )
        val body = gson.toJson(ChatRequest(model, messages))
        val request = HttpRequest.newBuilder(URI.create("$endpoint/api/chat"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = send(request)
        if (response.statusCode() !in 200..299) {
            throw ModelUnavailableException("Ollama вернула HTTP ${response.statusCode()}: ${errorText(response.body())}")
        }
        val payload = parse<ChatResponse>(response.body(), "Ollama вернула некорректный JSON.")
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
                return@withContext unavailable("HTTP ${response.statusCode()}: ${errorText(response.body())}")
            }
            val models = parse<TagsResponse>(response.body(), "Ollama вернула некорректный список моделей.")
                .models.mapNotNull { it.name ?: it.model }.distinct().sorted()
            val selected = selectModel(configuredModel, models)
            val installed = selected != null
            resolvedModel = selected
            ModelStatus(
                endpoint = endpoint,
                model = selected ?: configuredModel,
                reachable = true,
                installed = installed,
                availableModels = models,
                error = when {
                    models.isEmpty() -> "В Ollama нет установленных моделей."
                    else -> null
                },
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
        model = configuredModel,
        reachable = false,
        installed = false,
        error = message,
    )

    private fun connectionMessage(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val details = root.message?.takeIf(String::isNotBlank) ?: root::class.simpleName
        return "Не удалось подключиться к Ollama по адресу $endpoint. Причина: $details"
    }

    private fun errorText(body: String): String = runCatching {
        gson.fromJson(body, ErrorBody::class.java).error?.takeIf(String::isNotBlank)
    }.getOrNull() ?: body.trim().take(500).ifBlank { "нет описания" }

    private inline fun <reified T> parse(json: String, message: String): T =
        runCatching { gson.fromJson(json, T::class.java) }.getOrElse { throw ModelUnavailableException(message, it) }

    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val stream: Boolean = false,
    )

    private data class ChatResponse(val message: ChatMessage? = null)
    private data class TagsResponse(val models: List<TaggedModel> = emptyList())
    private data class TaggedModel(val name: String? = null, val model: String? = null)
    private data class ErrorBody(val error: String? = null)
}
