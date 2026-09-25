package scheduledmcp

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewsAgentTest {
    @Test
    fun `agent claims due work gets news and saves generated report`() = runBlocking {
        val gson = Gson()
        val schedule = NewsSchedule(
            id = "schedule-1",
            title = "Новости",
            instruction = "Собери топ новостей за сутки",
            cron = "0 18 * * *",
            runAt = null,
            timeZone = "Europe/Moscow",
            topLimit = 2,
            enabled = true,
            nextRunAt = "2026-09-25T15:00:00Z",
            createdAt = "2026-09-24T10:00:00Z",
        )
        val run = ReportRun(
            id = "run-1",
            scheduleId = schedule.id,
            title = schedule.title,
            scheduledFor = schedule.nextRunAt,
            startedAt = "2026-09-25T15:00:01Z",
            status = "running",
        )
        val batch = NewsBatch(
            source = "Hacker News",
            collectedAt = Instant.parse("2026-09-25T15:00:02Z").toString(),
            windowHours = 24,
            count = 1,
            stories = listOf(
                NewsStory(1, "Важная новость", "https://example.test/news", 42, 7, "author", "2026-09-25T12:00:00Z"),
            ),
        )
        val session = FakeNewsToolSession(
            gson.toJson(mapOf("dueReports" to listOf(DueReport(schedule, run)))),
            gson.toJson(batch),
        )
        val agent = NewsAgent(DemoNewsLanguageModel(), FakeNewsGateway(session), "Europe/Moscow")

        val reports = agent.runDue()

        assertEquals(1, reports.size)
        assertTrue(reports.single().report.contains("Важная новость"))
        assertEquals("run-1", session.completedArguments?.get("runId"))
        assertEquals(1, session.completedArguments?.get("storiesCount"))
    }
}

private class FakeNewsGateway(private val session: ToolSession) : ToolGateway {
    override suspend fun <T> withSession(block: suspend ToolSession.() -> T): T = session.block()
}

private class FakeNewsToolSession(
    private val dueJson: String,
    private val newsJson: String,
) : ToolSession {
    var completedArguments: Map<String, Any?>? = null

    override suspend fun listTools(): List<AgentTool> = listOf(
        AgentTool(GET_TOP_NEWS_TOOL, "Собирает новости", "{\"type\":\"object\"}"),
    )

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): String = when (name) {
        CLAIM_DUE_TOOL -> dueJson
        GET_TOP_NEWS_TOOL -> newsJson
        COMPLETE_REPORT_TOOL -> {
            completedArguments = arguments
            "{}"
        }
        else -> error("Unexpected tool $name")
    }
}
