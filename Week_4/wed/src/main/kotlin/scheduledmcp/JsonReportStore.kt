package scheduledmcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.io.path.exists

internal class JsonReportStore(
    private val file: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
) {
    init {
        if (!file.exists()) write(ReportState())
    }

    @Synchronized
    fun createSchedule(
        title: String,
        instruction: String,
        cron: String?,
        runAt: String?,
        timeZone: String,
        topLimit: Int,
    ): NewsSchedule {
        require(title.isNotBlank()) { "Название не должно быть пустым." }
        require(instruction.isNotBlank()) { "Инструкция не должна быть пустой." }
        require((cron == null) xor (runAt == null)) { "Укажите ровно одно из полей cron или runAt." }
        require(topLimit in 1..20) { "topLimit должен быть от 1 до 20." }
        val zone = ZoneId.of(timeZone)
        val now = clock.instant()
        val next = if (cron != null) {
            DailyCron.parse(cron).nextAfter(now, zone)
        } else {
            Instant.parse(requireNotNull(runAt))
        }
        val schedule = NewsSchedule(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            instruction = instruction.trim(),
            cron = cron?.trim(),
            runAt = runAt,
            timeZone = zone.id,
            topLimit = topLimit,
            enabled = true,
            nextRunAt = next.toString(),
            createdAt = now.toString(),
        )
        val state = read()
        write(state.copy(schedules = state.schedules + schedule))
        return schedule
    }

    @Synchronized
    fun listSchedules(): List<NewsSchedule> = read().schedules.sortedBy { it.nextRunAt }

    @Synchronized
    fun claimDue(now: Instant = clock.instant()): List<DueReport> {
        val state = read()
        val runningSchedules = state.runs
            .filter { it.status == RUNNING }
            .mapTo(mutableSetOf()) { it.scheduleId }
        val due = state.schedules.filter {
            it.enabled && it.id !in runningSchedules && !Instant.parse(it.nextRunAt).isAfter(now)
        }
        if (due.isEmpty()) return emptyList()

        val runs = due.map { schedule ->
            ReportRun(
                id = UUID.randomUUID().toString(),
                scheduleId = schedule.id,
                title = schedule.title,
                scheduledFor = schedule.nextRunAt,
                startedAt = now.toString(),
                status = RUNNING,
            )
        }
        val runBySchedule = runs.associateBy(ReportRun::scheduleId)
        val schedules = state.schedules.map { schedule ->
            val run = runBySchedule[schedule.id] ?: return@map schedule
            if (schedule.cron == null) {
                schedule.copy(enabled = false, lastRunAt = run.startedAt)
            } else {
                val next = DailyCron.parse(schedule.cron)
                    .nextAfter(now, ZoneId.of(schedule.timeZone))
                schedule.copy(lastRunAt = run.startedAt, nextRunAt = next.toString())
            }
        }
        write(state.copy(schedules = schedules, runs = (state.runs + runs).takeLast(1_000)))
        return due.map { schedule -> DueReport(schedule, runBySchedule.getValue(schedule.id)) }
    }

    @Synchronized
    fun completeRun(runId: String, report: String, storiesCount: Int): ReportRun {
        require(report.isNotBlank()) { "Отчёт не должен быть пустым." }
        require(storiesCount >= 0) { "storiesCount не может быть отрицательным." }
        return updateRun(runId) {
            require(it.status == RUNNING) { "Запуск '$runId' уже завершён." }
            it.copy(
                completedAt = clock.instant().toString(),
                status = COMPLETED,
                report = report.trim(),
                storiesCount = storiesCount,
            )
        }
    }

    @Synchronized
    fun failRun(runId: String, error: String): ReportRun = updateRun(runId) {
        require(it.status == RUNNING) { "Запуск '$runId' уже завершён." }
        it.copy(
            completedAt = clock.instant().toString(),
            status = FAILED,
            error = error.ifBlank { "Неизвестная ошибка" },
        )
    }

    @Synchronized
    fun history(scheduleId: String? = null, limit: Int = 20): ReportHistory {
        require(limit in 1..100) { "limit должен быть от 1 до 100." }
        val filtered = read().runs
            .filter { scheduleId == null || it.scheduleId == scheduleId }
            .sortedByDescending { it.startedAt }
        val completed = filtered.firstOrNull { it.status == COMPLETED }
        return ReportHistory(
            scheduleId = scheduleId,
            totalRuns = filtered.size,
            successfulRuns = filtered.count { it.status == COMPLETED },
            failedRuns = filtered.count { it.status == FAILED },
            runningRuns = filtered.count { it.status == RUNNING },
            latestCompletedAt = completed?.completedAt,
            latestReport = completed?.report,
            runs = filtered.take(limit),
        )
    }

    @Synchronized
    fun clearAll(): ClearNewsDataResult {
        val state = read()
        write(ReportState())
        return ClearNewsDataResult(
            deletedSchedules = state.schedules.size,
            deletedRuns = state.runs.size,
        )
    }

    private fun updateRun(runId: String, transform: (ReportRun) -> ReportRun): ReportRun {
        val state = read()
        val existing = state.runs.firstOrNull { it.id == runId }
            ?: error("Неизвестный запуск '$runId'.")
        val updated = transform(existing)
        write(state.copy(runs = state.runs.map { if (it.id == runId) updated else it }))
        return updated
    }

    private fun read(): ReportState {
        if (!file.exists()) return ReportState()
        val source = Files.readString(file)
        if (source.isBlank()) return ReportState()
        return gson.fromJson(source, ReportState::class.java)
    }

    private fun write(state: ReportState) {
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, gson.toJson(state))
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val RUNNING = "running"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
    }
}
