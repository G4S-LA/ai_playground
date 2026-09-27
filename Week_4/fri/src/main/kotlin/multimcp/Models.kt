package multimcp

import java.time.Instant

internal data class McpServerInfo(
    val id: String,
    val name: String,
    val description: String,
    val transport: String,
    val source: String,
)

internal data class AgentMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<ModelToolCall> = emptyList(),
    val toolCallId: String? = null,
)

internal data class ModelToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

internal data class ModelTurn(
    val content: String,
    val toolCalls: List<ModelToolCall> = emptyList(),
)

internal data class AgentTool(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val serverId: String,
    val serverName: String,
)

internal data class ToolExecution(
    val name: String,
    val serverId: String,
    val serverName: String,
    val arguments: Map<String, Any?>,
    val result: String,
)

internal data class AgentRun(
    val answer: String,
    val executions: List<ToolExecution>,
)

internal data class AgentTraceEvent(
    val type: String,
    val message: String,
    val toolName: String? = null,
    val serverId: String? = null,
    val serverName: String? = null,
    val payload: String? = null,
    val createdAt: String = Instant.now().toString(),
)

internal data class McpTopology(
    val servers: List<McpServerInfo>,
    val tools: List<AgentTool>,
)

internal class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
