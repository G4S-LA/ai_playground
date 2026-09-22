package gitmcp

import java.time.Instant
import java.util.UUID

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
)

internal data class ToolExecution(
    val name: String,
    val result: String,
)

internal data class AgentReply(
    val answer: String,
    val toolExecutions: List<ToolExecution>,
)

internal data class ChatEntry(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val createdAt: String = Instant.now().toString(),
)

internal data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val messages: List<ChatEntry> = emptyList(),
)

internal data class SessionSummary(
    val id: String,
    val title: String,
    val updatedAt: String,
    val messageCount: Int,
)

internal data class ChatSnapshot(
    val session: ChatSession,
    val model: String,
)

internal data class MessageRequest(val message: String = "")
internal data class MessageResponse(
    val answer: String,
    val toolExecutions: List<ToolExecution>,
    val snapshot: ChatSnapshot,
)
internal data class ToolsResponse(val tools: List<AgentTool>)
internal data class SessionsResponse(val sessions: List<SessionSummary>)
internal data class CreateSessionResponse(val session: SessionSummary)
internal data class SnapshotResponse(val snapshot: ChatSnapshot)
internal data class ErrorResponse(val error: String)
