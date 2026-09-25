package scheduledmcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal interface NewsWebController {
    val modelName: String
    suspend fun schedule(message: String, conversation: List<AgentMessage>): ScheduledAnswer
    suspend fun schedules(): List<NewsSchedule>
    suspend fun history(): ReportHistory
    suspend fun runDue(): List<ExecutedReport>
    suspend fun clearData(): ClearNewsDataResult
}

internal class LiveNewsWebController(
    private val agent: NewsAgent,
    private val delivery: ReportDelivery,
    override val modelName: String,
) : NewsWebController {
    override suspend fun schedule(message: String, conversation: List<AgentMessage>): ScheduledAnswer =
        agent.scheduleWithDetails(message, conversation)
    override suspend fun schedules(): List<NewsSchedule> = agent.schedules()
    override suspend fun history(): ReportHistory = agent.reportHistory()
    override suspend fun runDue(): List<ExecutedReport> = agent.runDue().also { reports ->
        reports.forEach(delivery::deliver)
    }
    override suspend fun clearData(): ClearNewsDataResult = agent.clearData()
}

internal interface WebRunCoordinator {
    fun scheduleChanged()
    suspend fun runNow(): List<ExecutedReport>
    fun events(): WebRunnerEvents
}

internal class WebReportRunner(
    private val controller: NewsWebController,
    private val clock: Clock = Clock.systemUTC(),
    private val onReports: (List<ExecutedReport>) -> Unit = {},
) : WebRunCoordinator, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val execution = Mutex()

    @Volatile
    private var latestEvents = WebRunnerEvents()

    fun start() {
        scope.launch { loop() }
    }

    override fun scheduleChanged() {
        changed.trySend(Unit)
    }

    override suspend fun runNow(): List<ExecutedReport> = execution.withLock {
        try {
            controller.runDue().also { reports ->
                if (reports.isNotEmpty()) {
                    onReports(reports)
                    publish(reports = reports, error = null)
                }
            }
        } catch (error: Exception) {
            publish(reports = emptyList(), error = error.message ?: "Неизвестная ошибка запуска")
            throw error
        }
    }

    override fun events(): WebRunnerEvents = latestEvents

    override fun close() {
        scope.cancel()
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val schedules = try {
                controller.schedules()
            } catch (error: Exception) {
                publish(emptyList(), error.message ?: "Не удалось прочитать расписания")
                waitForChange(15_000)
                continue
            }
            val nextRun = schedules.asSequence()
                .filter(NewsSchedule::enabled)
                .mapNotNull { runCatching { Instant.parse(it.nextRunAt) }.getOrNull() }
                .minOrNull()
            if (nextRun == null) {
                changed.receive()
                continue
            }

            val waitMillis = Duration.between(clock.instant(), nextRun).toMillis().coerceAtLeast(1)
            if (waitForChange(waitMillis)) continue
            runCatching { runNow() }
            delay(500)
        }
    }

    private suspend fun waitForChange(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            changed.receive()
            true
        } ?: false

    @Synchronized
    private fun publish(reports: List<ExecutedReport>, error: String?) {
        latestEvents = WebRunnerEvents(
            version = latestEvents.version + 1,
            reports = reports,
            lastError = error,
        )
    }
}
