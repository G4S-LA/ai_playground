package multimcp

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
    fun `web exposes topology and right-side MCP highlighting`() = testApplication {
        application { multiMcpWebModule(testAgent()) }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.body<String>()
        assertTrue("MCP-маршрутизация" in html)
        assertTrue("mcp-servers" in html)
        assertTrue("Задействованные MCP-серверы" in html)

        val script = client.get("/static/app.js")
        assertEquals(HttpStatusCode.OK, script.status)
        val javaScript = script.body<String>()
        assertTrue("updateTopologyState" in javaScript)
        assertTrue("mcp-server--active" in javaScript)

        val topology = client.get("/api/topology")
        assertEquals(HttpStatusCode.OK, topology.status)
        val topologyJson = topology.body<String>()
        assertTrue("News MCP" in topologyJson)
        assertTrue("Frankfurter MCP" in topologyJson)
        assertTrue("Excel MCP" in topologyJson)
        assertTrue(INSPECT_WORKBOOK_TOOL in topologyJson)
    }

    @Test
    fun `run endpoint exposes server ownership for every routed tool call`() = testApplication {
        application { multiMcpWebModule(testAgent()) }

        val started = client.post("/api/runs") {
            contentType(ContentType.Application.Json)
            setBody("""{"request":"Get Hacker News and USD rates, save them to an Excel workbook"}""")
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
        assertTrue("model_tool_selected" in snapshot)
        assertTrue("tool_started" in snapshot)
        assertTrue("tool_completed" in snapshot)
        assertTrue("pipeline_completed" in snapshot)
        assertTrue("\"serverId\": \"news\"" in snapshot)
        assertTrue("\"serverId\": \"currency\"" in snapshot)
        assertTrue("\"serverId\": \"excel\"" in snapshot)
        assertTrue(INSPECT_WORKBOOK_TOOL in snapshot)
    }

    private fun testAgent(): MultiMcpAgent = MultiMcpAgent(
        model = DemoMultiMcpLanguageModel(),
        gateway = RecordingToolGateway(),
        outputDirectory = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "multi-mcp-web-tests"),
    )
}
