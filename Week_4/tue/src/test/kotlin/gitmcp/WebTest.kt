package gitmcp

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebTest {
    @Test
    fun `web chat exposes MCP tools without previous assignment controls`() = testApplication {
        application { chatModule(testAgent()) }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.body<String>()
        assertTrue("Git Agent" in html)
        assertTrue("MCP-инструменты" in html)
        assertTrue("Диалоги" in html)
        assertFalse("Слои памяти" in html)
        assertFalse("Планирование" in html)

        val script = client.get("/static/app.js")
        assertEquals(HttpStatusCode.OK, script.status)
        assertTrue("showTypingIndicator" in script.body<String>())
        val styles = client.get("/static/styles.css")
        assertEquals(HttpStatusCode.OK, styles.status)
        assertTrue("@keyframes typing-bounce" in styles.body<String>())

        val tools = client.get("/api/tools")
        assertEquals(HttpStatusCode.OK, tools.status)
        assertTrue(GIT_SUMMARY_TOOL in tools.body<String>())
    }

    @Test
    fun `web chat returns answer produced after tool call`() = testApplication {
        val agent = testAgent()
        val session = agent.createSession()
        application { chatModule(agent) }

        val response = client.post("/api/sessions/${session.id}/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"Show repository status"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<String>()
        assertTrue("fixture result" in body)
        assertTrue(GIT_SUMMARY_TOOL in body)

        assertEquals(HttpStatusCode.OK, client.delete("/api/sessions/${session.id}").status)
        assertFalse(session.id in client.get("/api/sessions").body<String>())
    }

    private fun testAgent(): ChatAgent = ChatAgent(
        model = DemoLanguageModel(),
        tools = FakeToolGateway(),
        store = FileChatStore(kotlin.io.path.createTempDirectory("git-mcp-web-test-")),
        systemPrompt = "You are a test agent.",
        repositoryPath = "/tmp/example",
        modelName = "demo-test",
    )
}

private class FakeToolGateway : ToolGateway {
    override suspend fun <T> withSession(block: suspend ToolSession.() -> T): T = FakeToolSession.block()
}

private object FakeToolSession : ToolSession {
    override suspend fun listTools(): List<AgentTool> = listOf(
        AgentTool(
            name = GIT_SUMMARY_TOOL,
            description = "Test Git tool",
            inputSchemaJson = """{"type":"object","properties":{}}""",
        ),
    )

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): String = "fixture result"
}
