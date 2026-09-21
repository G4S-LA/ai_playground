package mcpdemo

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.runBlocking

public fun main(args: Array<String>) {
    KotlinLoggingConfiguration.logStartupMessage = false

    runBlocking {
        val serverUrl = System.getenv("MCP_SERVER_URL") ?: DEFAULT_MCP_SERVER_URL
        val tools = listAvailableTools(serverUrl)
        println("MCP connection established: $serverUrl")
        println("Available tools: ${tools.size}")
        tools.forEach { tool ->
            println("- ${tool.name}: ${tool.description ?: "No description"}")
            println("  input schema: ${tool.inputSchema}")
        }
    }
}
