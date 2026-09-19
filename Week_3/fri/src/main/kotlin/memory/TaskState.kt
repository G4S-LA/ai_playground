package memory

import java.time.Instant

enum class TaskStage(val wireName: String) {
    IDLE("idle"),
    PLANNING("planning"),
    EXECUTION("execution"),
    VALIDATION("validation"),
    BLOCKED("blocked"),
    FAILED("failed"),
    DONE("done");

    companion object {
        fun fromWireName(value: String): TaskStage = entries.firstOrNull {
            it.wireName == value.trim().lowercase()
        } ?: throw IllegalArgumentException(
            "Неизвестный этап '$value'. Допустимо: ${entries.joinToString { it.wireName }}."
        )
    }
}

/**
 * Переход задаётся бизнес-событием, а не желаемым target stage. Поэтому вызывающий
 * код не может выставить произвольное состояние и обойти обязательный этап.
 */
enum class TaskEvent(val wireName: String) {
    START_TASK("start_task"),
    PLAN_READY("plan_ready"),
    REVISE_PLAN("revise_plan"),
    APPROVE_PLAN("approve_plan"),
    COMPLETE_EXECUTION("complete_execution"),
    PASS_VALIDATION("pass_validation"),
    REJECT_VALIDATION("reject_validation"),
    EXHAUST_VALIDATION("exhaust_validation"),
    BLOCK("block"),
    RESUME("resume"),
    FAIL("fail"),
    RETRY("retry"),
}

data class TaskState(
    val stage: String,
    val currentStep: Int,
    val expectedAction: String,
    val updatedAt: String,
    val planningApplied: Boolean = false,
    val planReady: Boolean = false,
    val planApproved: Boolean = false,
    val validationAttempts: Int = 0,
    val recoveryStage: String? = null,
    val lastFailure: String? = null,
) {
    fun stageValue(): TaskStage = TaskStage.fromWireName(stage)

    companion object {
        fun initial(now: String = Instant.now().toString()) = TaskState(
            stage = TaskStage.IDLE.wireName,
            currentStep = 0,
            expectedAction = "Ожидается запрос пользователя",
            updatedAt = now,
        )
    }
}

data class TaskStateView(
    val stage: String,
    val currentStep: Int,
    val expectedAction: String,
    val updatedAt: String,
    val allowedEvents: List<String>,
    val planningApplied: Boolean,
    val planReady: Boolean,
    val planApproved: Boolean,
    val validationAttempts: Int,
    val recoveryStage: String?,
    val lastFailure: String?,
)

sealed interface TransitionResult {
    val state: TaskState

    data class Accepted(
        override val state: TaskState,
        val event: TaskEvent,
        val previousStage: String,
    ) : TransitionResult

    data class Rejected(
        override val state: TaskState,
        val event: TaskEvent,
        val reason: String,
        val allowedEvents: List<String>,
    ) : TransitionResult
}

class TaskStateMachine {
    fun dispatch(
        current: TaskState,
        event: TaskEvent,
        expectedAction: String,
        failureReason: String? = null,
    ): TransitionResult {
        val source = current.stageValue()
        val allowed = allowedEvents(current)
        if (event !in allowed) {
            return TransitionResult.Rejected(
                state = current,
                event = event,
                reason = rejectionReason(source, event),
                allowedEvents = allowed.map { it.wireName },
            )
        }

        val target = targetFor(current, event)
        val normalizedAction = validateAction(expectedAction)
        val normalizedFailure = failureReason?.trim()?.takeIf { it.isNotEmpty() }?.take(1_000)
        if (event in setOf(TaskEvent.BLOCK, TaskEvent.FAIL, TaskEvent.EXHAUST_VALIDATION)) {
            requireNotNull(normalizedFailure) { "Для события ${event.wireName} нужно указать причину." }
        }

        val starting = event == TaskEvent.START_TASK
        val planningApplied = when (event) {
            TaskEvent.START_TASK -> true
            else -> current.planningApplied
        }
        val planApproved = when (event) {
            TaskEvent.APPROVE_PLAN -> true
            TaskEvent.START_TASK, TaskEvent.REVISE_PLAN -> false
            else -> current.planApproved
        }
        val planReady = when (event) {
            TaskEvent.PLAN_READY, TaskEvent.APPROVE_PLAN -> true
            TaskEvent.START_TASK, TaskEvent.REVISE_PLAN -> false
            else -> current.planReady
        }
        val validationAttempts = when (event) {
            TaskEvent.START_TASK, TaskEvent.RESUME, TaskEvent.RETRY -> 0
            TaskEvent.REJECT_VALIDATION, TaskEvent.EXHAUST_VALIDATION -> current.validationAttempts + 1
            else -> current.validationAttempts
        }
        val recoveryStage = when (event) {
            TaskEvent.BLOCK, TaskEvent.FAIL -> source.wireName
            TaskEvent.EXHAUST_VALIDATION -> TaskStage.EXECUTION.wireName
            TaskEvent.RESUME, TaskEvent.RETRY -> null
            else -> current.recoveryStage
        }

        val updated = TaskState(
            stage = target.wireName,
            currentStep = if (starting) 1 else current.currentStep + 1,
            expectedAction = normalizedAction,
            updatedAt = Instant.now().toString(),
            planningApplied = planningApplied,
            planReady = planReady,
            planApproved = planApproved,
            validationAttempts = validationAttempts,
            recoveryStage = recoveryStage,
            lastFailure = when (event) {
                TaskEvent.BLOCK, TaskEvent.FAIL, TaskEvent.EXHAUST_VALIDATION -> normalizedFailure
                TaskEvent.RESUME, TaskEvent.RETRY,
                TaskEvent.START_TASK -> null
                else -> current.lastFailure
            },
        )
        return TransitionResult.Accepted(updated, event, source.wireName)
    }

    fun allowedEvents(state: TaskState): Set<TaskEvent> = when (state.stageValue()) {
        TaskStage.IDLE, TaskStage.DONE -> setOf(TaskEvent.START_TASK)
        TaskStage.PLANNING -> if (state.planReady) {
            setOf(TaskEvent.APPROVE_PLAN, TaskEvent.REVISE_PLAN, TaskEvent.BLOCK, TaskEvent.FAIL)
        } else {
            setOf(TaskEvent.PLAN_READY, TaskEvent.BLOCK, TaskEvent.FAIL)
        }
        TaskStage.EXECUTION -> setOf(
            TaskEvent.COMPLETE_EXECUTION,
            TaskEvent.BLOCK,
            TaskEvent.FAIL,
        )
        TaskStage.VALIDATION -> setOf(
            TaskEvent.PASS_VALIDATION,
            TaskEvent.REJECT_VALIDATION,
            TaskEvent.EXHAUST_VALIDATION,
            TaskEvent.BLOCK,
            TaskEvent.FAIL,
        )
        TaskStage.BLOCKED -> setOf(TaskEvent.RESUME, TaskEvent.FAIL)
        TaskStage.FAILED -> setOf(
            TaskEvent.RETRY,
            TaskEvent.START_TASK,
        )
    }

    fun view(state: TaskState): TaskStateView = TaskStateView(
        stage = state.stageValue().wireName,
        currentStep = state.currentStep,
        expectedAction = state.expectedAction,
        updatedAt = state.updatedAt,
        allowedEvents = allowedEvents(state).map { it.wireName },
        planningApplied = state.planningApplied,
        planReady = state.planReady,
        planApproved = state.planApproved,
        validationAttempts = state.validationAttempts,
        recoveryStage = state.recoveryStage,
        lastFailure = state.lastFailure,
    )

    private fun targetFor(current: TaskState, event: TaskEvent): TaskStage = when (event) {
        TaskEvent.START_TASK, TaskEvent.PLAN_READY, TaskEvent.REVISE_PLAN -> TaskStage.PLANNING
        TaskEvent.APPROVE_PLAN, TaskEvent.REJECT_VALIDATION -> TaskStage.EXECUTION
        TaskEvent.COMPLETE_EXECUTION -> TaskStage.VALIDATION
        TaskEvent.PASS_VALIDATION -> TaskStage.DONE
        TaskEvent.EXHAUST_VALIDATION, TaskEvent.BLOCK -> TaskStage.BLOCKED
        TaskEvent.FAIL -> TaskStage.FAILED
        TaskEvent.RESUME, TaskEvent.RETRY -> TaskStage.fromWireName(
            requireNotNull(current.recoveryStage) { "Не указан этап восстановления." }
        )
    }

    private fun rejectionReason(source: TaskStage, event: TaskEvent): String = when {
        event == TaskEvent.COMPLETE_EXECUTION && source == TaskStage.PLANNING ->
            "Нельзя начать или завершить реализацию до утверждения плана."
        event == TaskEvent.PASS_VALIDATION && source != TaskStage.VALIDATION ->
            "Нельзя завершить задачу без этапа validation."
        source == TaskStage.DONE -> "Задача уже завершена; начните новый цикл."
        else -> "Событие ${event.wireName} недопустимо на этапе ${source.wireName}."
    }

    private fun validateAction(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Ожидаемое действие не должно быть пустым." }
        require(normalized.length <= 1_000) { "Ожидаемое действие не должно превышать 1000 символов." }
        return normalized
    }
}
