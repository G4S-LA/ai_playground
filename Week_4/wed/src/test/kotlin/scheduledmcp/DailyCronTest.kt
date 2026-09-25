package scheduledmcp

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DailyCronTest {
    @Test
    fun `calculates next daily run in requested time zone`() {
        val cron = DailyCron.parse("0 18 * * *")
        val moscow = ZoneId.of("Europe/Moscow")

        assertEquals(
            Instant.parse("2026-09-25T15:00:00Z"),
            cron.nextAfter(Instant.parse("2026-09-25T14:59:00Z"), moscow),
        )
        assertEquals(
            Instant.parse("2026-09-26T15:00:00Z"),
            cron.nextAfter(Instant.parse("2026-09-25T15:00:00Z"), moscow),
        )
    }
}
