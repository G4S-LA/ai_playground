package localrag

import com.google.gson.Gson
import docindex.EmbeddingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LocalOllamaEmbeddingProvider(
    private val baseUrl: String,
    private val model: String,
    timeoutSeconds: Long,
    private val gson: Gson = Gson(),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) : EmbeddingProvider {
    private val timeout = Duration.ofSeconds(timeoutSeconds)
    override val description: String = "Ollama · $model"

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/embed"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(EmbedRequest(model, texts))))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException(
                "Не удалось подключиться к локальной Ollama по адресу $baseUrl. Запустите `ollama serve`.",
                error,
            )
        }
        check(response.statusCode() in 200..299) {
            "Ollama вернула HTTP ${response.statusCode()} для embeddings: ${response.body().take(500)}"
        }
        val payload = runCatching { gson.fromJson(response.body(), EmbedResponse::class.java) }
            .getOrElse { throw IllegalStateException("Ollama вернула некорректный JSON embeddings.", it) }
        check(payload.embeddings.size == texts.size) {
            "Ollama вернула ${payload.embeddings.size} embeddings для ${texts.size} текстов."
        }
        payload.embeddings.map { it.toFloatArray() }
    }

    private data class EmbedRequest(val model: String, val input: List<String>)
    private data class EmbedResponse(val embeddings: List<List<Float>> = emptyList())
}
