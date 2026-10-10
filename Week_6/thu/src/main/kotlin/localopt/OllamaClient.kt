package localopt

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LocalModelException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class OllamaClient(
    private val endpoint: String,
    private val model: String,
    timeoutSeconds: Long,
    private val gson: Gson = Gson(),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) : LocalModelClient {
    private val timeout = Duration.ofSeconds(timeoutSeconds)

    override suspend fun generate(prompt: String, profile: GenerationProfile): GenerationRun =
        withContext(Dispatchers.IO) {
            val payload = ChatRequest(
                model = model,
                messages = buildList {
                    if (profile.systemPrompt.isNotBlank()) add(Message("system", profile.systemPrompt))
                    add(Message("user", prompt))
                },
                options = Options(
                    temperature = profile.temperature,
                    maxTokens = profile.maxTokens,
                    contextWindow = profile.contextWindow,
                ),
            )
            val response = sendJson("/api/chat", payload)
            if (response.statusCode() !in 200..299) throw responseError(response)
            val body = parse<ChatResponse>(response.body(), "Ollama вернула некорректный JSON ответа.")
            val answer = body.message?.content?.trim().orEmpty()
            if (answer.isEmpty()) throw LocalModelException("Локальная модель вернула пустой ответ.")
            val resources = processSnapshot()
            val evalDuration = body.evalDuration ?: 0L
            val responseTokens = body.evalCount ?: 0
            GenerationRun(
                profile = profile,
                answer = answer,
                metrics = GenerationMetrics(
                    totalMs = body.totalDuration.toMilliseconds(),
                    loadMs = body.loadDuration.toMilliseconds(),
                    promptEvalMs = body.promptEvalDuration.toMilliseconds(),
                    generationMs = evalDuration.toMilliseconds(),
                    promptTokens = body.promptEvalCount ?: 0,
                    responseTokens = responseTokens,
                    tokensPerSecond = if (evalDuration > 0L) responseTokens * 1_000_000_000.0 / evalDuration else 0.0,
                    resources = resources,
                ),
            )
        }

    override suspend fun info(): ModelInfo = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create("$endpoint/api/tags"))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build()
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return@withContext unavailable("HTTP ${response.statusCode()}: ${errorText(response.body())}")
            }
            val tags = parse<TagsResponse>(response.body(), "Ollama вернула некорректный список моделей.")
            val names = tags.models.mapNotNull { it.name ?: it.model }.distinct().sorted()
            val selected = tags.models.firstOrNull { sameModel(it.name ?: it.model.orEmpty(), model) }
            ModelInfo(
                endpoint = endpoint,
                model = model,
                reachable = true,
                installed = selected != null,
                parameterSize = selected?.details?.parameterSize,
                quantization = selected?.details?.quantizationLevel,
                modelBytes = selected?.size,
                availableModels = names,
            )
        } catch (error: Exception) {
            unavailable(connectionMessage(error))
        }
    }

    private fun processSnapshot(): ResourceSnapshot = try {
        val request = HttpRequest.newBuilder(URI.create("$endpoint/api/ps"))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return ResourceSnapshot()
        val running = parse<PsResponse>(response.body(), "Ollama вернула некорректный /api/ps.")
            .models.firstOrNull { sameModel(it.name ?: it.model.orEmpty(), model) }
        ResourceSnapshot(
            loadedBytes = running?.size,
            vramBytes = running?.sizeVram,
            runnerContextLength = running?.contextLength,
        )
    } catch (_: Exception) {
        ResourceSnapshot()
    }

    private fun sendJson(path: String, payload: Any): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$endpoint$path"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()
        return try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw LocalModelException(connectionMessage(error), error)
        }
    }

    private fun responseError(response: HttpResponse<String>) = LocalModelException(
        "Ollama вернула HTTP ${response.statusCode()}: ${errorText(response.body())}",
    )

    private fun errorText(body: String): String = runCatching {
        gson.fromJson(body, ErrorBody::class.java).error?.takeIf(String::isNotBlank)
    }.getOrNull() ?: body.trim().take(500).ifBlank { "нет описания" }

    private fun unavailable(message: String) = ModelInfo(
        endpoint = endpoint,
        model = model,
        reachable = false,
        installed = false,
        error = message,
    )

    private fun connectionMessage(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val details = root.message?.takeIf(String::isNotBlank) ?: root::class.simpleName
        return "Не удалось подключиться к локальной Ollama по адресу $endpoint. " +
            "Запустите `ollama serve`. Причина: $details"
    }

    private fun sameModel(installed: String, requested: String): Boolean =
        installed == requested || installed.removeSuffix(":latest") == requested.removeSuffix(":latest")

    private fun Long?.toMilliseconds(): Long = (this ?: 0L) / 1_000_000L

    private inline fun <reified T> parse(json: String, message: String): T =
        runCatching { gson.fromJson(json, T::class.java) }.getOrElse { throw LocalModelException(message, it) }

    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val stream: Boolean = false,
        val options: Options,
    )

    private data class Options(
        val temperature: Double,
        @SerializedName("num_predict") val maxTokens: Int,
        @SerializedName("num_ctx") val contextWindow: Int,
    )

    private data class Message(val role: String = "", val content: String = "")

    private data class ChatResponse(
        val message: Message? = null,
        @SerializedName("total_duration") val totalDuration: Long? = null,
        @SerializedName("load_duration") val loadDuration: Long? = null,
        @SerializedName("prompt_eval_count") val promptEvalCount: Int? = null,
        @SerializedName("prompt_eval_duration") val promptEvalDuration: Long? = null,
        @SerializedName("eval_count") val evalCount: Int? = null,
        @SerializedName("eval_duration") val evalDuration: Long? = null,
    )

    private data class TagsResponse(val models: List<TaggedModel> = emptyList())

    private data class TaggedModel(
        val name: String? = null,
        val model: String? = null,
        val size: Long? = null,
        val details: ModelDetails? = null,
    )

    private data class ModelDetails(
        @SerializedName("parameter_size") val parameterSize: String? = null,
        @SerializedName("quantization_level") val quantizationLevel: String? = null,
    )

    private data class PsResponse(val models: List<RunningModel> = emptyList())

    private data class RunningModel(
        val name: String? = null,
        val model: String? = null,
        val size: Long? = null,
        @SerializedName("size_vram") val sizeVram: Long? = null,
        @SerializedName("context_length") val contextLength: Int? = null,
    )

    private data class ErrorBody(val error: String? = null)
}
