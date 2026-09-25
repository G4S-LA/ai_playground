package scheduledmcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class NewsMcpGateway(
    private val config: AppConfig,
) : ToolGateway {
    override suspend fun <T> withSession(block: suspend ToolSession.() -> T): T {
        val process = startServerProcess()
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered(),
        )
        val client = Client(
            clientInfo = Implementation(name = "scheduled-news-agent", version = "1.0.0"),
        )
        return try {
            client.connect(transport)
            ClientToolSession(client).block()
        } finally {
            client.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    private fun startServerProcess(): Process {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", executable).toString()
        return ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "scheduledmcp.MainKt",
            "server",
        ).apply {
            environment()["REPORT_DATA_FILE"] = config.dataFile.toString()
            environment()["REPORT_TIME_ZONE"] = config.defaultTimeZone
            environment()["NEWS_API_BASE_URL"] = config.newsApiBaseUrl
            environment()["NEWS_CANDIDATE_LIMIT"] = config.newsCandidateLimit.toString()
        }.start()
    }
}

private class ClientToolSession(private val client: Client) : ToolSession {
    override suspend fun listTools(): List<AgentTool> = client.listTools().tools.map(Tool::toAgentTool)

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): String {
        val result = client.callTool(name, arguments)
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        check(result.isError != true) { text.ifBlank { "MCP tool '$name' завершился с ошибкой." } }
        return text
    }
}

private fun Tool.toAgentTool(): AgentTool {
    val schema = buildJsonObject {
        put("type", "object")
        put("properties", inputSchema.properties ?: buildJsonObject {})
        inputSchema.required?.let { required ->
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }
    return AgentTool(name, description.orEmpty(), schema.toString())
}
