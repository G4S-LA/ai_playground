package scheduledmcp

data class NewsSchedule(
    val id: String,
    val title: String,
    val instruction: String,
    val cron: String?,
    val runAt: String?,
    val timeZone: String,
    val topLimit: Int,
    val enabled: Boolean,
    val nextRunAt: String,
    val createdAt: String,
    val lastRunAt: String? = null,
)

data class ReportRun(
    val id: String,
    val scheduleId: String,
    val title: String,
    val scheduledFor: String,
    val startedAt: String,
    val completedAt: String? = null,
    val status: String,
    val report: String? = null,
    val storiesCount: Int? = null,
    val error: String? = null,
)

data class DueReport(
    val schedule: NewsSchedule,
    val run: ReportRun,
)

data class NewsStory(
    val id: Long,
    val title: String,
    val url: String,
    val score: Int,
    val comments: Int,
    val author: String,
    val publishedAt: String,
)

data class ReportHistory(
    val scheduleId: String?,
    val totalRuns: Int,
    val successfulRuns: Int,
    val failedRuns: Int,
    val runningRuns: Int,
    val latestCompletedAt: String?,
    val latestReport: String?,
    val runs: List<ReportRun>,
)

data class ClearNewsDataResult(
    val deletedSchedules: Int,
    val deletedRuns: Int,
)

internal data class ReportState(
    val version: Int = 1,
    val schedules: List<NewsSchedule> = emptyList(),
    val runs: List<ReportRun> = emptyList(),
)
