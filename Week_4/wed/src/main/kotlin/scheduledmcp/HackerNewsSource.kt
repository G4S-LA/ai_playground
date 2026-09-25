package scheduledmcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal data class NewsBatch(
    val source: String,
    val collectedAt: String,
    val windowHours: Int,
    val count: Int,
    val stories: List<NewsStory>,
)

internal fun interface NewsSource {
    fun topStories(windowHours: Int, limit: Int): NewsBatch
}

internal class HackerNewsSource(
    private val baseUrl: String,
    private val candidateLimit: Int,
    private val clock: Clock = Clock.systemUTC(),
    private val gson: Gson = Gson(),
    private val fetchJson: (URI) -> String = defaultFetcher(),
) : NewsSource {
    override fun topStories(windowHours: Int, limit: Int): NewsBatch {
        require(windowHours in 1..168) { "windowHours должен быть от 1 до 168." }
        require(limit in 1..20) { "limit должен быть от 1 до 20." }
        val collectedAt = clock.instant()
        val cutoff = collectedAt.minus(Duration.ofHours(windowHours.toLong())).epochSecond
        val ids = gson.fromJson(
            fetchJson(URI.create("${baseUrl.trimEnd('/')}/topstories.json")),
            LongArray::class.java,
        ).take(candidateLimit)
        val stories = ids.mapNotNull { id ->
            runCatching {
                val json = fetchJson(URI.create("${baseUrl.trimEnd('/')}/item/$id.json"))
                gson.fromJson(json, JsonObject::class.java)?.toStory()
            }.getOrNull()
        }.filter { story ->
            Instant.parse(story.publishedAt).epochSecond >= cutoff
        }.sortedWith(
            compareByDescending<NewsStory> { it.score }
                .thenByDescending { it.comments }
                .thenByDescending { it.publishedAt },
        ).take(limit)

        return NewsBatch(
            source = "Hacker News",
            collectedAt = collectedAt.toString(),
            windowHours = windowHours,
            count = stories.size,
            stories = stories,
        )
    }

    private fun JsonObject.toStory(): NewsStory? {
        if (get("type")?.asString != "story") return null
        if (get("deleted")?.asBoolean == true || get("dead")?.asBoolean == true) return null
        val id = get("id")?.asLong ?: return null
        val title = get("title")?.asString?.trim().orEmpty()
        if (title.isEmpty()) return null
        return NewsStory(
            id = id,
            title = title,
            url = get("url")?.asString ?: "https://news.ycombinator.com/item?id=$id",
            score = get("score")?.asInt ?: 0,
            comments = get("descendants")?.asInt ?: 0,
            author = get("by")?.asString.orEmpty(),
            publishedAt = Instant.ofEpochSecond(get("time")?.asLong ?: return null).toString(),
        )
    }

    private companion object {
        fun defaultFetcher(): (URI) -> String {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
            return { uri ->
                val request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "scheduled-news-mcp/1.0")
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                check(response.statusCode() in 200..299) {
                    "News API вернул HTTP ${response.statusCode()} для $uri"
                }
                response.body()
            }
        }
    }
}
