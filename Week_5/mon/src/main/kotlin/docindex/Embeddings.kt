package docindex

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.abs
import kotlin.math.sqrt

interface EmbeddingProvider {
    val description: String
    suspend fun embed(texts: List<String>): List<FloatArray>
}

class OllamaEmbeddingProvider(
    private val baseUrl: String,
    private val model: String,
    private val gson: Gson = Gson(),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : EmbeddingProvider {
    override val description: String = "Ollama · $model"

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/embed"))
            .timeout(Duration.ofMinutes(2))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(OllamaEmbedRequest(model, texts))))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException(
                "Не удалось подключиться к Ollama по адресу $baseUrl. Запустите Ollama или используйте --demo.",
                error,
            )
        }
        check(response.statusCode() in 200..299) {
            "Ollama вернула HTTP ${response.statusCode()}: ${response.body().take(500)}"
        }
        val payload = gson.fromJson(response.body(), OllamaEmbedResponse::class.java)
        check(payload.embeddings.size == texts.size) {
            "Ollama вернула ${payload.embeddings.size} эмбеддингов для ${texts.size} текстов."
        }
        payload.embeddings.map { values -> values.toFloatArray() }
    }

    private data class OllamaEmbedRequest(val model: String, val input: List<String>)
    private data class OllamaEmbedResponse(val embeddings: List<List<Float>> = emptyList())
}

class DemoEmbeddingProvider(private val dimensions: Int = 384) : EmbeddingProvider {
    override val description: String = "Demo · hashing ($dimensions)"

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map(::vectorize)

    private fun vectorize(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        val tokens = TOKEN.findAll(text.lowercase()).map { it.value }.toList()
        (tokens + tokens.zipWithNext { left, right -> "$left::$right" }).forEach { token ->
            val hash = token.hashCode()
            val index = abs(hash % dimensions)
            vector[index] += if (hash and 1 == 0) 1f else -1f
        }
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) vector.indices.forEach { vector[it] /= norm }
        return vector
    }

    private companion object {
        val TOKEN = Regex("[\\p{L}\\p{N}_]{2,}")
    }
}
