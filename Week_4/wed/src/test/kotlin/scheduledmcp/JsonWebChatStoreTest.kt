package scheduledmcp

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonWebChatStoreTest {
    @Test
    fun `persists multiple dialogs and attaches report to matching dialog`() {
        val directory = createTempDirectory("news-web-chats-")
        try {
            val clock = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC)
            val file = directory.resolve("chats.json")
            val store = JsonWebChatStore(file, clock)
            val first = store.create()
            val second = store.create()
            store.addMessage(first.id, "user", "Собери новости")
            store.addMessage(first.id, "assistant", "Расписание готово", scheduleId = "schedule-1")
            store.addReports(listOf(executedReport("schedule-1", "run-1", "Итоговый отчёт")))

            val restored = JsonWebChatStore(file, clock)
            assertEquals(2, restored.list().size)
            assertTrue(restored.get(first.id).messages.last().content.contains("Итоговый отчёт"))
            assertEquals("run-1", restored.get(first.id).messages.last().runId)

            restored.delete(second.id)
            assertEquals(listOf(first.id), restored.list().map { it.id })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun executedReport(scheduleId: String, runId: String, report: String): ExecutedReport {
        val schedule = NewsSchedule(
            id = scheduleId,
            title = "Новости",
            instruction = "Собери новости",
            cron = null,
            runAt = "2026-09-25T12:00:00Z",
            timeZone = "Europe/Moscow",
            topLimit = 5,
            enabled = false,
            nextRunAt = "2026-09-25T12:00:00Z",
            createdAt = "2026-09-25T11:00:00Z",
        )
        val run = ReportRun(
            id = runId,
            scheduleId = scheduleId,
            title = schedule.title,
            scheduledFor = schedule.nextRunAt,
            startedAt = schedule.nextRunAt,
            completedAt = "2026-09-25T12:00:03Z",
            status = "completed",
            report = report,
            storiesCount = 3,
        )
        return ExecutedReport(DueReport(schedule, run), report, 3)
    }
}
