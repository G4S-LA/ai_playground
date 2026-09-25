package toolpipeline

internal data class SearchItem(
    val id: String,
    val title: String,
    val url: String,
    val content: String,
)

internal data class SearchResult(
    val query: String,
    val items: List<SearchItem>,
)

internal data class SummaryResult(
    val sourceQuery: String,
    val sourceCount: Int,
    val summary: String,
    val sourceIds: List<String>,
)

internal data class SavedFileResult(
    val path: String,
    val bytes: Long,
    val sourceCount: Int,
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
)

internal data class ToolExecution(
    val name: String,
    val arguments: Map<String, Any?>,
    val result: String,
)

internal data class PipelineRun(
    val answer: String,
    val executions: List<ToolExecution>,
)

internal class PipelineException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
