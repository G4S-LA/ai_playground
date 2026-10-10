package localopt

import com.google.gson.JsonParser
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebTest {
    @Test
    fun `web compares two profiles of the same model`() = testApplication {
        val fake = object : LocalModelClient {
            override suspend fun generate(prompt: String, profile: GenerationProfile) =
                GenerationRun(profile, "${profile.id}: $prompt", metrics())

            override suspend fun info() = modelInfo()
        }
        application { optimizationModule(ComparisonService(fake, baseline(), optimized())) }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("Same model, better behavior"))

        val info = JsonParser.parseString(client.get("/api/info").bodyAsText()).asJsonObject
        assertEquals("Q4_K_M", info.getAsJsonObject("model").get("quantization").asString)

        val response = client.post("/api/compare") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"Объясни корутины"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val comparison = JsonParser.parseString(response.bodyAsText()).asJsonObject
        assertEquals("sequential", comparison.get("executionMode").asString)
        assertEquals("baseline: Объясни корутины", comparison.getAsJsonObject("baseline").get("answer").asString)
        assertEquals("optimized: Объясни корутины", comparison.getAsJsonObject("optimized").get("answer").asString)
    }
}
