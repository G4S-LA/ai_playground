package scheduledmcp

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

internal data class ExecutedReport(
    val due: DueReport,
    val report: String,
    val storiesCount: Int,
)

internal data class ScheduledAnswer(
    val answer: String,
    val schedule: NewsSchedule,
)

internal class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
