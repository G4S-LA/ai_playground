package toolpipeline

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
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineMcpServerTest {
    @Test
    fun `server exposes schemas and executes the full chain`() = runBlocking {
        val output = createTempDirectory("tool-pipeline-server-")
        val process = startServer(output)
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered(),
        )
        val client = Client(clientInfo = Implementation(name = "pipeline-test", version = "1.0.0"))
        val gson = Gson()
        try {
            client.connect(transport)
            val tools = client.listTools().tools
            assertEquals(listOf(SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TOOL), tools.map { it.name })
            assertContains(tools.single { it.name == SUMMARIZE_TOOL }.inputSchema.required.orEmpty(), "searchResult")
            assertContains(tools.single { it.name == SAVE_TOOL }.inputSchema.required.orEmpty(), "summary")

            val search = client.callTool(SEARCH_TOOL, mapOf("query" to "MCP pipeline", "limit" to 2)).text()
            val searchObject = gson.fromJson(search, JsonObject::class.java)
            assertFalse(searchObject.getAsJsonArray("items").isEmpty)

            val summary = client.callTool(
                SUMMARIZE_TOOL,
                mapOf("searchResult" to gson.fromJson(search, Map::class.java), "maxSentences" to 2),
            ).text()
            val summaryObject = gson.fromJson(summary, JsonObject::class.java)
            assertEquals("MCP pipeline", summaryObject.get("sourceQuery").asString)

            val saved = client.callTool(
                SAVE_TOOL,
                mapOf("summary" to gson.fromJson(summary, Map::class.java), "fileName" to "integration.md"),
            ).text()
            assertTrue(output.resolve("integration.md").exists())
            assertEquals(2, gson.fromJson(saved, JsonObject::class.java).get("sourceCount").asInt)
        } finally {
            client.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            output.toFile().deleteRecursively()
        }
    }

    private fun startServer(outputDirectory: Path): Process {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", executable).toString()
        return ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "toolpipeline.MainKt", "server")
            .apply { environment()["PIPELINE_OUTPUT_DIR"] = outputDirectory.toString() }
            .start()
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.text(): String {
        val text = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        check(isError != true) { text.ifBlank { "MCP tool call failed" } }
        return text
    }
}
