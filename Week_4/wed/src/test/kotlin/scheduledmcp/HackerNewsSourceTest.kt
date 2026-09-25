package scheduledmcp

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class HackerNewsSourceTest {
    @Test
    fun `filters last day and sorts stories by score`() {
        val now = Instant.parse("2026-09-25T12:00:00Z")
        val recent = now.minusSeconds(3_600).epochSecond
        val old = now.minusSeconds(90_000).epochSecond
        val jsonByPath = mapOf(
            "/v0/topstories.json" to "[1,2,3,4]",
            "/v0/item/1.json" to story(1, "Первая", recent, 10, url = "https://one.test"),
            "/v0/item/2.json" to story(2, "Лидер", recent, 50),
            "/v0/item/3.json" to story(3, "Старая", old, 100),
            "/v0/item/4.json" to "{\"id\":4,\"type\":\"comment\",\"time\":$recent}",
        )
        val source = HackerNewsSource(
            baseUrl = "https://example.test/v0",
            candidateLimit = 10,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            fetchJson = { uri -> jsonByPath.getValue(uri.path) },
        )

        val batch = source.topStories(windowHours = 24, limit = 10)

        assertEquals(2, batch.count)
        assertEquals(listOf("Лидер", "Первая"), batch.stories.map { it.title })
        assertEquals("https://news.ycombinator.com/item?id=2", batch.stories.first().url)
    }

    private fun story(id: Int, title: String, time: Long, score: Int, url: String? = null): String =
        """{"id":$id,"type":"story","title":"$title","time":$time,"score":$score,"descendants":3,"by":"author"${url?.let { ",\"url\":\"$it\"" }.orEmpty()}}"""
}
