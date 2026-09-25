package scheduledmcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class NewsMcpServerTest {
    @Test
    fun `MCP client schedules claims and completes a report`() = runBlocking {
        val directory = createTempDirectory("news-mcp-integration-")
        val process = startServer(directory.resolve("reports.json"))
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered(),
        )
        val client = Client(clientInfo = Implementation(name = "news-mcp-test", version = "1.0.0"))
        val gson = Gson()
        try {
            client.connect(transport)
            val tools = client.listTools().tools
            assertContains(tools.map { it.name }, SCHEDULE_REPORT_TOOL)
            assertContains(tools.single { it.name == GET_TOP_NEWS_TOOL }.description.orEmpty(), "Собирает")

            client.callTool(
                SCHEDULE_REPORT_TOOL,
                mapOf(
                    "title" to "Интеграционный отчёт",
                    "instruction" to "Собери новости",
                    "runAt" to "2026-09-25T10:00:00Z",
                    "timeZone" to "Europe/Moscow",
                    "topLimit" to 3,
                ),
            ).text()
            val dueRoot = gson.fromJson(
                client.callTool(CLAIM_DUE_TOOL, mapOf("now" to "2026-09-25T11:00:00Z")).text(),
                JsonObject::class.java,
            )
            val runId = dueRoot.getAsJsonArray("dueReports")[0].asJsonObject
                .getAsJsonObject("run").get("id").asString

            client.callTool(
                COMPLETE_REPORT_TOOL,
                mapOf("runId" to runId, "report" to "Отчёт готов", "storiesCount" to 3),
            ).text()
            val history = gson.fromJson(
                client.callTool(REPORT_HISTORY_TOOL, mapOf("limit" to 10)).text(),
                JsonObject::class.java,
            )
            assertEquals(1, history.get("successfulRuns").asInt)
            assertEquals("Отчёт готов", history.get("latestReport").asString)

            val cleared = gson.fromJson(
                client.callTool(CLEAR_NEWS_DATA_TOOL, emptyMap()).text(),
                JsonObject::class.java,
            )
            assertEquals(1, cleared.get("deletedSchedules").asInt)
            assertEquals(1, cleared.get("deletedRuns").asInt)
            val emptyHistory = gson.fromJson(
                client.callTool(REPORT_HISTORY_TOOL, emptyMap()).text(),
                JsonObject::class.java,
            )
            assertEquals(0, emptyHistory.get("totalRuns").asInt)
        } finally {
            client.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            directory.toFile().deleteRecursively()
        }
    }

    private fun startServer(dataFile: Path): Process {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", executable).toString()
        return ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "scheduledmcp.MainKt", "server")
            .apply {
                environment()["REPORT_DATA_FILE"] = dataFile.toString()
                environment()["NEWS_CANDIDATE_LIMIT"] = "10"
            }
            .start()
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.text(): String {
        val text = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        check(isError != true) { text.ifBlank { "MCP tool call failed" } }
        return text
    }
}
