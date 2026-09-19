package memory

class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class LifecycleException(message: String) : IllegalStateException(message)

class MemoryAgent(
    private val store: FileMemoryStore,
    private val model: LanguageModel,
    systemPrompt: String,
    private val modelName: String,
    private val planningPolicy: PlanningPolicy = LlmPlanningPolicy(model),
) {
    private val promptBuilder = PromptBuilder(systemPrompt)
    private val stateMachine = TaskStateMachine()

    fun listSessions(): List<SessionSummary> = store.listSessions()

    fun createSession(): SessionSummary = store.createSession()

    fun snapshot(sessionId: String): AgentSnapshot = store.snapshot(sessionId).withModel()

    @Synchronized
    fun reply(
        sessionId: String,
        rawMessage: String,
        onStateChange: (TaskState) -> Unit = {},
    ): MessageResponse {
        val message = rawMessage.trim()
        require(message.isNotEmpty()) { "Введите непустое сообщение." }
        require(message.length <= 4_000) { "Сообщение не должно превышать 4000 символов." }

        val trace = mutableListOf<TaskState>()
        try {
            val initialStage = store.snapshot(sessionId).taskState.stageValue()
            if (initialStage == TaskStage.BLOCKED) {
                emit(
                    move(sessionId, TaskEvent.RESUME, "Продолжить задачу с учётом уточнения"),
                    trace,
                    onStateChange,
                )
            } else {
                store.clearWorkingPlan(sessionId)
                startNewTask(sessionId, message, trace, onStateChange)
            }

            var answer = requireOutput(
                model.complete(promptBuilder.build(store.snapshot(sessionId), message)),
                "Модель вернула пустой ответ.",
            )
            var validationAttempt = 0
            while (true) {
                emit(
                    move(
                        sessionId,
                        TaskEvent.COMPLETE_EXECUTION,
                        "Проверить полноту и корректность ответа",
                    ),
                    trace,
                    onStateChange,
                )
                val feedback = validationFeedback(
                    model.complete(promptBuilder.buildValidation(store.snapshot(sessionId), message, answer))
                )
                if (feedback == null) {
                    emit(
                        move(sessionId, TaskEvent.PASS_VALIDATION, "Ответ проверен и готов"),
                        trace,
                        onStateChange,
                    )
                    store.appendTurn(sessionId, message, answer, trace)
                    return MessageResponse(answer, snapshot(sessionId))
                }

                if (validationAttempt == MAX_VALIDATION_ATTEMPTS - 1) {
                    emit(
                        move(
                            sessionId,
                            TaskEvent.EXHAUST_VALIDATION,
                            "Ожидается уточнение пользователя",
                            feedback,
                        ),
                        trace,
                        onStateChange,
                    )
                    store.appendTurn(sessionId, message, CLARIFICATION_RESPONSE, trace)
                    return MessageResponse(CLARIFICATION_RESPONSE, snapshot(sessionId))
                }

                emit(
                    move(
                        sessionId,
                        TaskEvent.REJECT_VALIDATION,
                        "Исправить ответ по замечаниям проверки",
                    ),
                    trace,
                    onStateChange,
                )
                answer = requireOutput(
                    model.complete(
                        promptBuilder.buildRevision(store.snapshot(sessionId), message, answer, feedback)
                    ),
                    "Модель не вернула исправленный ответ.",
                )
                validationAttempt += 1
            }
        } catch (error: AgentException) {
            markFailed(sessionId, error.message ?: "Неизвестная ошибка модели", trace, onStateChange)
            throw error
        }
    }

    fun remember(
        sessionId: String,
        layerName: String,
        category: String,
        content: String,
    ): RememberResponse {
        val layer = MemoryLayer.fromWireName(layerName)
        val item = store.remember(sessionId, layer, category, content)
        return RememberResponse(item, snapshot(sessionId))
    }

    fun forget(sessionId: String, layerName: String, memoryId: String): AgentSnapshot {
        store.forget(sessionId, MemoryLayer.fromWireName(layerName), memoryId)
        return snapshot(sessionId)
    }

    fun deleteSession(sessionId: String) = store.deleteSession(sessionId)

    fun promptPreview(sessionId: String, query: String): List<PromptMessage> =
        promptBuilder.build(store.snapshot(sessionId), query)

    private fun startNewTask(
        sessionId: String,
        message: String,
        trace: MutableList<TaskState>,
        callback: (TaskState) -> Unit,
    ) {
        if (planningPolicy.needsPlanning(message)) {
            emit(
                move(sessionId, TaskEvent.START_WITH_PLAN, "Составить план выполнения запроса"),
                trace,
                callback,
            )
            val plan = requireOutput(
                model.complete(promptBuilder.build(store.snapshot(sessionId), message)),
                "Модель не составила план.",
            )
            store.replaceWorkingPlan(sessionId, plan)
            emit(
                move(sessionId, TaskEvent.APPROVE_PLAN, "Выполнить утверждённый план"),
                trace,
                callback,
            )
        } else {
            emit(
                move(sessionId, TaskEvent.START_WITHOUT_PLAN, "Выполнить простой запрос пользователя"),
                trace,
                callback,
            )
        }
    }

    private fun move(
        sessionId: String,
        event: TaskEvent,
        expectedAction: String,
        failureReason: String? = null,
    ): TaskState = when (
        val result = store.applyTaskEvent(sessionId, event, expectedAction, failureReason)
    ) {
        is TransitionResult.Accepted -> result.state
        is TransitionResult.Rejected -> throw LifecycleException(
            "${result.reason} Разрешённые события: " +
                result.allowedEvents.joinToString().ifEmpty { "нет" }
        )
    }

    private fun markFailed(
        sessionId: String,
        reason: String,
        trace: MutableList<TaskState>,
        callback: (TaskState) -> Unit,
    ) {
        val result = store.applyTaskEvent(
            sessionId,
            TaskEvent.FAIL,
            "Повторить задачу или начать новую",
            reason,
        )
        if (result is TransitionResult.Accepted) emit(result.state, trace, callback)
    }

    private fun emit(
        state: TaskState,
        trace: MutableList<TaskState>,
        callback: (TaskState) -> Unit,
    ) {
        trace += state
        callback(state)
    }

    private fun requireOutput(raw: String, errorMessage: String): String = raw.trim().also {
        if (it.isEmpty()) throw AgentException(errorMessage)
    }

    private fun validationFeedback(raw: String): String? {
        val verdict = requireOutput(raw, "Модель не вернула результат проверки.")
        val lines = verdict.lines()
        val firstLine = lines.first().trim().trim('`', '*', '#', ' ').uppercase()
        if (firstLine.startsWith("VALID")) return null
        if (firstLine.startsWith("REVISE")) {
            val inline = lines.first().substringAfter(':', "").trim()
            return (listOf(inline) + lines.drop(1)).joinToString("\n").trim()
                .ifEmpty { "Исправить ответ согласно результату проверки." }
        }
        throw AgentException("Не удалось распознать результат проверки модели: ${verdict.take(160)}")
    }

    private fun MemorySnapshot.withModel() = AgentSnapshot(
        session = session,
        working = working,
        longTerm = longTerm,
        taskState = stateMachine.view(taskState),
        model = modelName,
    )

    private companion object {
        const val MAX_VALIDATION_ATTEMPTS = 2
        const val CLARIFICATION_RESPONSE =
            "Проверка дважды нашла проблемы. Уточните требования, чтобы я продолжил эту задачу."
    }
}
