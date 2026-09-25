package scheduledmcp

data class WebChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: String,
    val scheduleId: String? = null,
    val runId: String? = null,
)

data class WebChatSession(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val messages: List<WebChatMessage> = emptyList(),
)

data class WebSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: String,
    val messageCount: Int,
)

internal data class WebChatState(
    val version: Int = 1,
    val sessions: List<WebChatSession> = emptyList(),
)

internal data class CreateSessionResponse(val session: WebChatSession)
internal data class SessionsResponse(val sessions: List<WebSessionSummary>)
internal data class SessionResponse(val session: WebChatSession)
internal data class MessageRequest(val message: String = "")
internal data class MessageResponse(val session: WebChatSession, val schedule: NewsSchedule)
internal data class WebDashboardResponse(
    val model: String,
    val now: String,
    val schedules: List<NewsSchedule>,
    val history: ReportHistory,
)

internal data class WebRunResponse(val reports: List<ExecutedReport>)
internal data class ClearDataResponse(val result: ClearNewsDataResult)
internal data class WebRunnerEvents(
    val version: Long = 0,
    val reports: List<ExecutedReport> = emptyList(),
    val lastError: String? = null,
)

internal data class ErrorResponse(val error: String)
