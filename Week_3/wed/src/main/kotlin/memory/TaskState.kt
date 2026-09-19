package memory

import java.time.Instant

enum class TaskStage(val wireName: String) {
    IDLE("idle"),
    PLANNING("planning"),
    EXECUTION("execution"),
    VALIDATION("validation"),
    DONE("done");

    fun allowedTargets(): Set<TaskStage> = when (this) {
        IDLE -> setOf(PLANNING, EXECUTION)
        PLANNING -> setOf(EXECUTION)
        EXECUTION -> setOf(PLANNING, VALIDATION)
        VALIDATION -> setOf(EXECUTION, DONE)
        DONE -> emptySet()
    }

    companion object {
        fun fromWireName(value: String): TaskStage = entries.firstOrNull {
            it.wireName == value.trim().lowercase()
        } ?: throw IllegalArgumentException(
            "Неизвестный этап '$value'. Допустимо: ${entries.joinToString { it.wireName }}."
        )
    }
}

data class TaskState(
    val stage: String,
    val currentStep: Int,
    val expectedAction: String,
    val updatedAt: String,
    val planningApplied: Boolean = false,
) {
    fun stageValue(): TaskStage = TaskStage.fromWireName(stage)

    companion object {
        fun initial(now: String = Instant.now().toString()) = TaskState(
            stage = TaskStage.IDLE.wireName,
            currentStep = 0,
            expectedAction = "Ожидается запрос пользователя",
            updatedAt = now,
            planningApplied = false,
        )
    }
}

data class TaskStateView(
    val stage: String,
    val currentStep: Int,
    val expectedAction: String,
    val updatedAt: String,
    val allowedTransitions: List<String>,
    val planningApplied: Boolean,
)

class TaskStateMachine {
    fun start(stage: TaskStage, expectedAction: String): TaskState {
        require(stage == TaskStage.PLANNING || stage == TaskStage.EXECUTION) {
            "Новый цикл можно начать только с planning или execution."
        }
        return TaskState(
            stage = stage.wireName,
            currentStep = 1,
            expectedAction = validateAction(expectedAction),
            updatedAt = Instant.now().toString(),
            planningApplied = stage == TaskStage.PLANNING,
        )
    }

    fun transition(
        current: TaskState,
        targetStage: String,
        currentStep: Int,
        expectedAction: String,
    ): TaskState {
        val source = current.stageValue()
        val target = TaskStage.fromWireName(targetStage)
        require(target in source.allowedTargets()) {
            "Переход ${source.wireName} → ${target.wireName} запрещён. " +
                if (source.allowedTargets().isEmpty()) {
                    "Задача уже завершена."
                } else {
                    "Допустимо: ${source.allowedTargets().joinToString { it.wireName }}."
                }
        }
        return TaskState(
            stage = target.wireName,
            currentStep = validateStep(currentStep),
            expectedAction = validateAction(expectedAction),
            updatedAt = Instant.now().toString(),
            planningApplied = current.planningApplied,
        )
    }

    fun view(state: TaskState): TaskStateView = TaskStateView(
        stage = state.stageValue().wireName,
        currentStep = state.currentStep,
        expectedAction = state.expectedAction,
        updatedAt = state.updatedAt,
        allowedTransitions = state.stageValue().allowedTargets().map { it.wireName },
        planningApplied = state.planningApplied,
    )

    private fun validateStep(value: Int): Int {
        require(value > 0) { "Номер текущего шага должен быть положительным." }
        return value
    }

    private fun validateAction(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Ожидаемое действие не должно быть пустым." }
        require(normalized.length <= 1_000) { "Ожидаемое действие не должно превышать 1000 символов." }
        return normalized
    }
}
