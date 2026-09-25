package toolpipeline

import com.google.gson.Gson
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebTest {
    @Test
    fun `web page contains right-side live pipeline trace`() = testApplication {
        application { pipelineWebModule(testAgent()) }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.body<String>()
        assertTrue("Что делает модель" in html)
        assertTrue("stage-search" in html)
        assertTrue("stage-summarize" in html)
        assertTrue("stage-saveToFile" in html)

        val script = client.get("/static/app.js")
        assertEquals(HttpStatusCode.OK, script.status)
        assertTrue("pollRun" in script.body<String>())

        val tools = client.get("/api/tools")
        assertEquals(HttpStatusCode.OK, tools.status)
        assertTrue(SAVE_TOOL in tools.body<String>())
    }

    @Test
    fun `run endpoint exposes model and tool events while agent works`() = testApplication {
        application { pipelineWebModule(testAgent()) }

        val started = client.post("/api/runs") {
            contentType(ContentType.Application.Json)
            setBody("""{"request":"Run web pipeline"}""")
        }
        assertEquals(HttpStatusCode.Accepted, started.status)
        val startBody = started.body<String>()
        val runId = requireNotNull(Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(startBody))
            .groupValues[1]

        var snapshot = startBody
        repeat(100) {
            if ("\"status\": \"completed\"" !in snapshot) {
                delay(10)
                snapshot = client.get("/api/runs/$runId").body()
            }
        }

        assertTrue("\"status\": \"completed\"" in snapshot)
        assertTrue("model_started" in snapshot)
        assertTrue("model_tool_selected" in snapshot)
        assertTrue("tool_started" in snapshot)
        assertTrue("tool_completed" in snapshot)
        assertTrue("pipeline_completed" in snapshot)
        assertTrue("saveToFile" in snapshot)
        assertTrue("Пайплайн завершён" in snapshot)
    }

    private fun testAgent(): PipelineAgent = PipelineAgent(
        model = DemoPipelineLanguageModel(query = "MCP pipeline", fileName = "web-test.md"),
        gateway = WebFakeToolGateway(),
    )
}

private class WebFakeToolGateway : ToolGateway {
    override suspend fun <T> withSession(block: suspend ToolSession.() -> T): T = WebFakeToolSession.block()
}

private object WebFakeToolSession : ToolSession {
    private val gson = Gson()

    override suspend fun listTools(): List<AgentTool> = listOf(SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TOOL).map { name ->
        AgentTool(name, "Test tool $name", """{"type":"object","properties":{}}""")
    }

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): String = when (name) {
        SEARCH_TOOL -> gson.toJson(
            SearchResult(
                query = "MCP pipeline",
                items = listOf(SearchItem("web", "Web trace", "https://example.test", "Trace every call.")),
            ),
        )
        SUMMARIZE_TOOL -> gson.toJson(
            SummaryResult("MCP pipeline", 1, "- Web trace: Trace every call.", listOf("web")),
        )
        SAVE_TOOL -> gson.toJson(SavedFileResult("/tmp/web-test.md", 42, 1))
        else -> error("Unknown tool $name")
    }
}
