package scheduledmcp

import com.google.gson.Gson
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebTest {
    @Test
    fun `web creates dialogs and schedules report through agent`() = testApplication {
        val directory = createTempDirectory("news-web-test-")
        val chats = JsonWebChatStore(directory.resolve("chats.json"))
        val controller = FakeNewsWebController()
        val runner = FakeWebRunCoordinator()
        application { newsAgentWebModule(controller, chats, runner) }
        try {
            val page = client.get("/")
            assertEquals(HttpStatusCode.OK, page.status)
            assertContains(page.bodyAsText(), "Мои диалоги")
            assertContains(page.bodyAsText(), "Расписания")
            assertContains(page.bodyAsText(), "Очистить задания и историю")

            val createdText = client.post("/api/sessions") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.bodyAsText()
            val sessionId = Gson().fromJson(createdText, CreateSessionResponse::class.java).session.id

            val reply = client.post("/api/sessions/$sessionId/messages") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"Через 30 секунд пришли сводку"}""")
            }
            assertEquals(HttpStatusCode.OK, reply.status)
            assertContains(reply.bodyAsText(), "schedule-1")
            assertEquals(1, runner.changedCount)

            val dashboard = client.get("/api/dashboard")
            assertContains(dashboard.bodyAsText(), "test-model")
            assertContains(dashboard.bodyAsText(), "schedule-1")

            val session = client.get("/api/sessions/$sessionId").bodyAsText()
            assertContains(session, "Через 30 секунд")
            assertContains(session, "Расписание сохранено")

            val cleared = client.delete("/api/dashboard")
            assertEquals(HttpStatusCode.OK, cleared.status)
            assertContains(cleared.bodyAsText(), "deletedSchedules")
            assertEquals(2, runner.changedCount)
            assertEquals(1, controller.clearCount)

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/sessions/$sessionId").status)
            assertTrue(chats.list().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private class FakeNewsWebController : NewsWebController {
    override val modelName: String = "test-model"
    var clearCount = 0
    private val schedule = NewsSchedule(
        id = "schedule-1",
        title = "Новости через 30 секунд",
        instruction = "Собери новости",
        cron = null,
        runAt = "2026-09-25T12:00:30Z",
        timeZone = "Europe/Moscow",
        topLimit = 5,
        enabled = true,
        nextRunAt = "2026-09-25T12:00:30Z",
        createdAt = "2026-09-25T12:00:00Z",
    )

    override suspend fun schedule(message: String, conversation: List<AgentMessage>): ScheduledAnswer =
        ScheduledAnswer("Расписание сохранено", schedule)

    override suspend fun schedules(): List<NewsSchedule> = listOf(schedule)

    override suspend fun history(): ReportHistory = ReportHistory(
        scheduleId = null,
        totalRuns = 0,
        successfulRuns = 0,
        failedRuns = 0,
        runningRuns = 0,
        latestCompletedAt = null,
        latestReport = null,
        runs = emptyList(),
    )

    override suspend fun runDue(): List<ExecutedReport> = emptyList()

    override suspend fun clearData(): ClearNewsDataResult {
        clearCount++
        return ClearNewsDataResult(deletedSchedules = 1, deletedRuns = 0)
    }
}

private class FakeWebRunCoordinator : WebRunCoordinator {
    var changedCount = 0

    override fun scheduleChanged() {
        changedCount++
    }

    override suspend fun runNow(): List<ExecutedReport> = emptyList()
    override fun events(): WebRunnerEvents = WebRunnerEvents()
}
