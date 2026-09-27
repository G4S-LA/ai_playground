package multimcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class MultiMcpGateway(private val config: AppConfig) : ToolGateway {
    override suspend fun <T> withSession(block: suspend ToolSession.() -> T): T {
        require(Files.isExecutable(config.excelMcpExecutable)) {
            "Excel MCP is not installed at ${config.excelMcpExecutable}. Run ./setup-mcp.sh first."
        }
        val connections = mutableListOf<McpConnection>()
        return try {
            connections += connectNews()
            connections += connectCurrency()
            connections += connectExcel()
            val routes = linkedMapOf<String, McpConnection>()
            val tools = connections.flatMap { connection ->
                connection.client.listTools().tools.map { tool ->
                    val previous = routes.put(tool.name, connection)
                    if (previous != null) {
                        error("Duplicate tool name '${tool.name}' on ${previous.info.id} and ${connection.info.id}.")
                    }
                    tool.toAgentTool(connection.info)
                }
            }
            RoutedToolSession(MCP_SERVERS, tools, routes).block()
        } finally {
            for (connection in connections.asReversed()) close(connection)
        }
    }

    private suspend fun connectNews(): McpConnection {
        val info = MCP_SERVERS.single { it.id == NEWS_SERVER }
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", executable).toString()
        return connectStdio(
            info = info,
            command = listOf(
                java,
                "-Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener",
                "-cp",
                System.getProperty("java.class.path"),
                "scheduledmcp.MainKt",
                "server",
            ),
            environment = mapOf(
                "REPORT_DATA_FILE" to config.newsDataFile.toString(),
                "NEWS_CANDIDATE_LIMIT" to config.newsCandidateLimit.toString(),
            ),
        )
    }

    private suspend fun connectExcel(): McpConnection {
        val info = MCP_SERVERS.single { it.id == EXCEL_SERVER }
        return connectStdio(
            info = info,
            command = listOf(config.excelMcpExecutable.toString(), "stdio"),
        )
    }

    private suspend fun connectStdio(
        info: McpServerInfo,
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): McpConnection {
        val process = ProcessBuilder(command).apply {
            environment().putAll(environment)
        }.start()
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered(),
        )
        val client = newClient()
        return try {
            client.connect(transport)
            McpConnection(info = info, client = client, process = process)
        } catch (error: Exception) {
            runCatching { client.close() }
            stop(process)
            throw AgentException("Cannot connect to ${info.name}: ${error.message}", error)
        }
    }

    private suspend fun connectCurrency(): McpConnection {
        val info = MCP_SERVERS.single { it.id == CURRENCY_SERVER }
        val httpClient = HttpClient(CIO) { install(SSE) }
        val client = newClient()
        return try {
            client.connect(StreamableHttpClientTransport(httpClient, config.currencyMcpUrl))
            McpConnection(info = info, client = client, httpClient = httpClient)
        } catch (error: Exception) {
            runCatching { client.close() }
            httpClient.close()
            throw AgentException("Cannot connect to ${info.name}: ${error.message}", error)
        }
    }

    private fun newClient(): Client = Client(
        clientInfo = Implementation(name = "multi-mcp-news-fx-excel-agent", version = "2.0.0"),
    )

    private suspend fun close(connection: McpConnection) {
        runCatching { connection.client.close() }
        connection.process?.let(::stop)
        connection.httpClient?.close()
    }

    private fun stop(process: Process) {
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}

private data class McpConnection(
    val info: McpServerInfo,
    val client: Client,
    val process: Process? = null,
    val httpClient: HttpClient? = null,
)

private class RoutedToolSession(
    private val servers: List<McpServerInfo>,
    private val tools: List<AgentTool>,
    private val routes: Map<String, McpConnection>,
) : ToolSession {
    override fun listServers(): List<McpServerInfo> = servers

    override fun listTools(): List<AgentTool> = tools

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): String {
        val connection = routes[name] ?: throw AgentException("No MCP server owns tool '$name'.")
        val result = connection.client.callTool(name, arguments)
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        if (result.isError == true) {
            throw AgentException("${connection.info.name} tool '$name' failed: ${text.ifBlank { "unknown error" }}")
        }
        return text
    }
}

private fun Tool.toAgentTool(server: McpServerInfo): AgentTool {
    val schema = buildJsonObject {
        put("type", "object")
        put("properties", inputSchema.properties ?: buildJsonObject {})
        inputSchema.required?.let { required ->
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }
    return AgentTool(
        name = name,
        description = description.orEmpty(),
        inputSchemaJson = schema.toString(),
        serverId = server.id,
        serverName = server.name,
    )
}
