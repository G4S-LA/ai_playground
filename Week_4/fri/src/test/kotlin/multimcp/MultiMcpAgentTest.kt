package multimcp

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MultiMcpAgentTest {
    @Test
    fun `agent selects six tools routes three servers and combines data for Excel`() = runBlocking {
        val gateway = RecordingToolGateway()
        val trace = mutableListOf<AgentTraceEvent>()
        val outputDirectory = testOutputDirectory()
        val run = MultiMcpAgent(
            model = DemoMultiMcpLanguageModel(),
            gateway = gateway,
            outputDirectory = outputDirectory,
        ).run("Build a news and currency workbook", trace::add)

        assertEquals(DEMO_FLOW_ORDER, run.executions.map(ToolExecution::name))
        assertEquals(
            listOf(NEWS_SERVER, CURRENCY_SERVER, EXCEL_SERVER, EXCEL_SERVER, EXCEL_SERVER, EXCEL_SERVER),
            run.executions.map(ToolExecution::serverId),
        )
        assertEquals(DEMO_FLOW_ORDER, gateway.calls.map { it.first })
        assertEquals(DEMO_FLOW_ORDER, trace.filter { it.type == "tool_started" }.mapNotNull { it.toolName })
        assertEquals(7, trace.count { it.type == "model_started" })
        assertEquals("pipeline_completed", trace.last().type)

        val createArguments = run.executions.single { it.name == CREATE_WORKBOOK_TOOL }.arguments
        val workbook = createArguments.getValue("filepath") as String
        assertTrue(workbook.startsWith(outputDirectory.toAbsolutePath().normalize().toString()))
        assertTrue(Path.of(workbook).fileName.toString().startsWith("news-and-rates-"))
        assertTrue(workbook.endsWith(".xlsx"))
        assertEquals(false, createArguments["overwrite"])

        val writeArguments = run.executions.single { it.name == WRITE_EXCEL_TOOL }.arguments
        assertEquals(workbook, writeArguments["filepath"])
        val rows = writeArguments["data"] as List<*>
        assertEquals(3, rows.size)
        assertEquals(listOf("News", "FX", "FX"), rows.map { (it as Map<*, *>)["Category"] })

        val tableArguments = run.executions.single { it.name == CREATE_TABLE_TOOL }.arguments
        assertEquals("A1:F4", tableArguments["data_range"])
        assertTrue(run.answer.contains(workbook))
    }

    @Test
    fun `model may use only currency MCP for an exchange-rate request`() = runBlocking {
        val gateway = RecordingToolGateway()

        val run = MultiMcpAgent(DemoMultiMcpLanguageModel(), gateway, testOutputDirectory())
            .run("Show USD to EUR rate")

        assertEquals(listOf(GET_RATES_TOOL), run.executions.map(ToolExecution::name))
        assertEquals(listOf(CURRENCY_SERVER), run.executions.map(ToolExecution::serverId))
    }

    @Test
    fun `model may answer without MCP tools`() = runBlocking {
        val gateway = RecordingToolGateway()

        val run = MultiMcpAgent(DemoMultiMcpLanguageModel(), gateway, testOutputDirectory()).run("Say hello")

        assertTrue(run.executions.isEmpty())
        assertTrue(run.answer.contains("не нужны"))
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `agent refuses to overwrite an existing workbook`() = runBlocking {
        val existingWorkbook = Files.createTempFile("existing-mcp-result-", ".xlsx")
        try {
            val gateway = RecordingToolGateway()
            val model = object : LanguageModel {
                override fun complete(messages: List<AgentMessage>, tools: List<AgentTool>): ModelTurn = ModelTurn(
                    content = "",
                    toolCalls = listOf(
                        ModelToolCall(
                            id = "unsafe-create",
                            name = CREATE_WORKBOOK_TOOL,
                            argumentsJson =
                                """{"filepath":${Gson().toJson(existingWorkbook.toString())},"overwrite":true}""",
                        ),
                    ),
                )
            }

            val error = assertFailsWith<AgentException> {
                MultiMcpAgent(model, gateway, testOutputDirectory()).run("Overwrite the existing workbook")
            }

            assertTrue(error.message.orEmpty().contains("will not be overwritten"))
            assertTrue(gateway.calls.isEmpty())
        } finally {
            Files.deleteIfExists(existingWorkbook)
        }
    }
}

private fun testOutputDirectory(): Path =
    Path.of(System.getProperty("java.io.tmpdir"), "multi-mcp-agent-tests")

internal class RecordingToolGateway : ToolGateway {
    val calls = mutableListOf<Pair<String, Map<String, Any?>>>()

    override suspend fun <T> withSession(block: suspend ToolSession.() -> T): T = Session().block()

    private inner class Session : ToolSession {
        private val gson = Gson()
        private val serverByTool = mapOf(
            GET_TOP_NEWS_TOOL to MCP_SERVERS[0],
            GET_RATES_TOOL to MCP_SERVERS[1],
            CREATE_WORKBOOK_TOOL to MCP_SERVERS[2],
            WRITE_EXCEL_TOOL to MCP_SERVERS[2],
            CREATE_TABLE_TOOL to MCP_SERVERS[2],
            INSPECT_WORKBOOK_TOOL to MCP_SERVERS[2],
        )

        override fun listServers(): List<McpServerInfo> = MCP_SERVERS

        override fun listTools(): List<AgentTool> = DEMO_FLOW_ORDER.map { name ->
            val server = requireNotNull(serverByTool[name])
            AgentTool(name, "Test tool $name", """{"type":"object","properties":{}}""", server.id, server.name)
        }

        override suspend fun callTool(name: String, arguments: Map<String, Any?>): String {
            calls += name to arguments
            return when (name) {
                GET_TOP_NEWS_TOOL -> gson.toJson(
                    mapOf(
                        "source" to "Hacker News",
                        "stories" to listOf(
                            mapOf(
                                "id" to 1,
                                "title" to "MCP clients route tools",
                                "url" to "https://example.test/news",
                                "score" to 42,
                                "comments" to 7,
                                "author" to "agent",
                                "publishedAt" to "2026-09-27T10:00:00Z",
                            ),
                        ),
                    ),
                )
                GET_RATES_TOOL -> gson.toJson(
                    listOf(
                        mapOf("date" to "2026-09-27", "base" to "USD", "quote" to "EUR", "rate" to 0.85),
                        mapOf("date" to "2026-09-27", "base" to "USD", "quote" to "GBP", "rate" to 0.75),
                    ),
                )
                CREATE_WORKBOOK_TOOL -> "Workbook created"
                WRITE_EXCEL_TOOL -> "Wrote 3 rows"
                CREATE_TABLE_TOOL -> "Created table NewsAndRates"
                INSPECT_WORKBOOK_TOOL -> gson.toJson(
                    mapOf(
                        "filepath" to arguments["filepath"],
                        "sheets" to listOf(mapOf("name" to "Sheet1", "range" to "A1:F4")),
                    ),
                )
                else -> error("Unknown tool $name")
            }
        }
    }
}

class McpCatalogTest {
    @Test
    fun `catalog describes existing hosted and ready-made servers`() {
        assertEquals(listOf(NEWS_SERVER, CURRENCY_SERVER, EXCEL_SERVER), MCP_SERVERS.map(McpServerInfo::id))
        assertEquals(listOf("stdio", "Streamable HTTP", "stdio"), MCP_SERVERS.map(McpServerInfo::transport))
        assertTrue(MCP_SERVERS.single { it.id == NEWS_SERVER }.source.contains("Week_4/wed"))
        assertTrue(MCP_SERVERS.single { it.id == CURRENCY_SERVER }.source.contains("frankfurter"))
        assertTrue(MCP_SERVERS.single { it.id == EXCEL_SERVER }.source.contains("excel-mcp"))
    }
}

class AppConfigTest {
    @Test
    fun `Windows uses exe suffix for the installed Excel MCP`() {
        val previousOsName = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            val config = AppConfig.fromEnvironment(
                demo = true,
                environment = emptyMap(),
                dotenvPath = java.nio.file.Path.of("missing-test.env"),
            )

            assertTrue(config.excelMcpExecutable.toString().endsWith(".mcp/excel-mcp.exe"))
        } finally {
            if (previousOsName == null) {
                System.clearProperty("os.name")
            } else {
                System.setProperty("os.name", previousOsName)
            }
        }
    }
}
