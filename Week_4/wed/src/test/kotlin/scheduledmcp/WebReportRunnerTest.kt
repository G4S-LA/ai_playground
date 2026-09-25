package scheduledmcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebReportRunnerTest {
    @Test
    fun `automatically executes a due schedule and publishes report`() = runBlocking {
        val controller = DueWebController()
        var attached: List<ExecutedReport> = emptyList()
        val runner = WebReportRunner(controller, onReports = { attached = it })
        try {
            runner.start()
            withTimeout(3_000) {
                while (runner.events().reports.isEmpty()) delay(20)
            }

            assertEquals(1, controller.runCount)
            assertEquals("run-auto", attached.single().due.run.id)
            assertEquals("Автоматический отчёт", runner.events().reports.single().report)
            assertNull(runner.events().lastError)
        } finally {
            runner.close()
        }
    }
}

private class DueWebController : NewsWebController {
    override val modelName = "test-model"

    @Volatile
    var runCount = 0

    private val schedule = NewsSchedule(
        id = "schedule-auto",
        title = "Автоматический отчёт",
        instruction = "Собери новости",
        cron = null,
        runAt = "2020-01-01T00:00:00Z",
        timeZone = "Europe/Moscow",
        topLimit = 5,
        enabled = true,
        nextRunAt = "2020-01-01T00:00:00Z",
        createdAt = "2019-12-31T23:00:00Z",
    )

    override suspend fun schedule(message: String, conversation: List<AgentMessage>): ScheduledAnswer =
        ScheduledAnswer("Сохранено", schedule)

    override suspend fun schedules(): List<NewsSchedule> = if (runCount == 0) listOf(schedule) else emptyList()

    override suspend fun history(): ReportHistory = ReportHistory(
        scheduleId = null,
        totalRuns = runCount,
        successfulRuns = runCount,
        failedRuns = 0,
        runningRuns = 0,
        latestCompletedAt = null,
        latestReport = null,
        runs = emptyList(),
    )

    override suspend fun runDue(): List<ExecutedReport> {
        runCount++
        val run = ReportRun(
            id = "run-auto",
            scheduleId = schedule.id,
            title = schedule.title,
            scheduledFor = schedule.nextRunAt,
            startedAt = schedule.nextRunAt,
            completedAt = schedule.nextRunAt,
            status = "completed",
            report = "Автоматический отчёт",
            storiesCount = 2,
        )
        return listOf(ExecutedReport(DueReport(schedule, run), "Автоматический отчёт", 2))
    }

    override suspend fun clearData(): ClearNewsDataResult = ClearNewsDataResult(0, 0)
}
