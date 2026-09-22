package gitmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal const val GIT_SUMMARY_TOOL = "git_repository_summary"

internal suspend fun runGitMcpServer() {
    val git = GitService()
    val server = Server(
        serverInfo = Implementation(name = "git-mcp-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    )

    server.addTool(
        name = GIT_SUMMARY_TOOL,
        description = "Return the current branch status and recent commits of a local Git repository.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("repositoryPath") {
                    put("type", "string")
                    put("description", "Absolute or relative path to a local Git repository")
                }
                putJsonObject("commitLimit") {
                    put("type", "integer")
                    put("description", "Number of recent commits to return, from 1 to 20")
                    put("minimum", 1)
                    put("maximum", 20)
                    put("default", 5)
                }
            },
            required = listOf("repositoryPath"),
        ),
    ) { request ->
        try {
            val repositoryPath = requireNotNull(
                request.arguments?.get("repositoryPath")?.jsonPrimitive?.content,
            ) { "repositoryPath is required" }
            val commitLimit = request.arguments
                ?.get("commitLimit")
                ?.jsonPrimitive
                ?.intOrNull
                ?: 5

            CallToolResult(
                content = listOf(TextContent(git.repositorySummary(repositoryPath, commitLimit))),
                isError = false,
            )
        } catch (error: Exception) {
            CallToolResult(
                content = listOf(TextContent(error.message ?: "Git operation failed")),
                isError = true,
            )
        }
    }

    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )
    val closed = CompletableDeferred<Unit>()
    val session = server.createSession(transport)
    session.onClose { closed.complete(Unit) }
    closed.await()
    server.close()
}
