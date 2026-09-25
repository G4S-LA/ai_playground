package scheduledmcp

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonReportStoreTest {
    @Test
    fun `persists schedule and aggregated report history`() {
        val directory = createTempDirectory("news-report-store-")
        try {
            val now = Instant.parse("2026-09-25T12:00:00Z")
            val file = directory.resolve("reports.json")
            val store = JsonReportStore(file, Clock.fixed(now, ZoneOffset.UTC))
            val schedule = store.createSchedule(
                title = "Топ новостей",
                instruction = "Собери новости за сутки",
                cron = null,
                runAt = now.minusSeconds(60).toString(),
                timeZone = "Europe/Moscow",
                topLimit = 5,
            )

            val due = store.claimDue()
            assertEquals(1, due.size)
            assertEquals(schedule.id, due.single().schedule.id)
            store.completeRun(due.single().run.id, "Готовый отчёт", 4)

            val restored = JsonReportStore(file, Clock.fixed(now, ZoneOffset.UTC))
            val history = restored.history(schedule.id)
            assertEquals(1, history.totalRuns)
            assertEquals(1, history.successfulRuns)
            assertEquals("Готовый отчёт", history.latestReport)
            assertFalse(restored.listSchedules().single().enabled)
            assertTrue(Files.readString(file).contains("Готовый отчёт"))

            val cleared = restored.clearAll()
            assertEquals(1, cleared.deletedSchedules)
            assertEquals(1, cleared.deletedRuns)
            assertTrue(restored.listSchedules().isEmpty())
            assertEquals(0, restored.history().totalRuns)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
