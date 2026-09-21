package mcpdemo

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool

internal const val DEFAULT_MCP_SERVER_URL = "https://mcp.deepwiki.com/mcp"

internal suspend fun listAvailableTools(serverUrl: String = DEFAULT_MCP_SERVER_URL): List<Tool> {
    val httpClient = HttpClient(CIO) {
        install(SSE)
    }
    val client = Client(
        clientInfo = Implementation(
            name = "week-4-monday-client",
            version = "1.0.0",
        ),
    )

    return try {
        val transport = StreamableHttpClientTransport(
            client = httpClient,
            url = serverUrl,
        )
        client.connect(transport)
        client.listTools().tools
    } finally {
        client.close()
        httpClient.close()
    }
}
