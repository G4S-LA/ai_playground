package gitmcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class GitAgent {
    suspend fun inspectRepository(repositoryPath: String, commitLimit: Int = 5): String {
        val process = startServerProcess()
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered(),
        )
        val client = Client(
            clientInfo = Implementation(name = "git-agent", version = "1.0.0"),
        )

        return try {
            client.connect(transport)

            val tool = client.listTools().tools.singleOrNull { it.name == GIT_SUMMARY_TOOL }
            checkNotNull(tool) { "MCP server did not register $GIT_SUMMARY_TOOL" }

            val result = client.callTool(
                name = tool.name,
                arguments = mapOf(
                    "repositoryPath" to repositoryPath,
                    "commitLimit" to commitLimit,
                ),
            )
            val text = result.content
                .filterIsInstance<TextContent>()
                .joinToString(separator = "\n") { it.text }

            check(result.isError != true) { text.ifBlank { "MCP Git tool failed" } }
            text
        } finally {
            client.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun startServerProcess(): Process {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", executable).toString()

        return ProcessBuilder(
            java,
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
            "-cp",
            System.getProperty("java.class.path"),
            "gitmcp.MainKt",
            "server",
        ).start()
    }
}
