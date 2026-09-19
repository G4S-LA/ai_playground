package memory

class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class LifecycleException(message: String) : IllegalStateException(message)

class MemoryAgent(
    private val store: FileMemoryStore,
    private val model: LanguageModel,
    systemPrompt: String,
    private val modelName: String,
) {
    private val promptBuilder = PromptBuilder(systemPrompt)
    private val stateMachine = TaskStateMachine()

    fun listSessions(): List<SessionSummary> = store.listSessions()

    fun createSession(): SessionSummary = store.createSession()

    fun snapshot(sessionId: String): AgentSnapshot = store.snapshot(sessionId).withModel()

    /**
     * Новый запрос создаёт план и оставляет задачу в planning. Сообщение, присланное
     * во время planning, считается замечанием к плану. Из blocked оно уточняет задачу.
     */
    @Synchronized
    fun reply(
        sessionId: String,
        rawMessage: String,
        onStateChange: (TaskState) -> Unit = {},
    ): MessageResponse {
        val message = validateMessage(rawMessage)
        val trace = mutableListOf<TaskState>()
        return try {
            val currentState = store.snapshot(sessionId).taskState
            when (currentState.stageValue()) {
                TaskStage.IDLE, TaskStage.DONE, TaskStage.FAILED ->
                    createPlan(sessionId, message, trace, onStateChange)
                TaskStage.PLANNING -> if (currentState.planReady && looksLikeApproval(message)) {
                    remindAboutExplicitApproval(sessionId, message)
                } else {
                    revisePlan(sessionId, message, trace, onStateChange)
                }
                TaskStage.BLOCKED -> {
                    val task = requiredWorking(sessionId, "agent_task")
                    emit(
                        move(sessionId, TaskEvent.RESUME, "Продолжить реализацию с учётом уточнения"),
                        trace,
                        onStateChange,
                    )
                    executeAndValidate(
                        sessionId = sessionId,
                        query = "$task\n\nУточнение пользователя:\n$message",
                        transcriptUserText = message,
                        trace = trace,
                        callback = onStateChange,
                    )
                }
                TaskStage.EXECUTION, TaskStage.VALIDATION -> throw LifecycleException(
                    "Задача уже выполняется. Дождитесь завершения текущего этапа."
                )
            }
        } catch (error: AgentException) {
            markFailed(sessionId, error.message ?: "Неизвестная ошибка модели", trace, onStateChange)
            throw error
        }
    }

    /** Явный approval gate: только этот метод открывает planning → execution. */
    @Synchronized
    fun approvePlan(
        sessionId: String,
        onStateChange: (TaskState) -> Unit = {},
    ): MessageResponse {
        val trace = mutableListOf<TaskState>()
        return try {
            val snapshot = store.snapshot(sessionId)
            if (snapshot.taskState.stageValue() != TaskStage.PLANNING) {
                throw LifecycleException("Утвердить план можно только на этапе planning.")
            }
            if (!snapshot.taskState.planReady) {
                throw LifecycleException("План ещё не подготовлен.")
            }
            val task = requiredWorking(sessionId, "agent_task")
            requiredWorking(sessionId, "agent_plan")
            emit(
                move(sessionId, TaskEvent.APPROVE_PLAN, "Выполнить утверждённый план"),
                trace,
                onStateChange,
            )
            executeAndValidate(
                sessionId = sessionId,
                query = task,
                transcriptUserText = "План утверждён.",
                trace = trace,
                callback = onStateChange,
            )
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

    private fun createPlan(
        sessionId: String,
        message: String,
        trace: MutableList<TaskState>,
        callback: (TaskState) -> Unit,
    ): MessageResponse {
        store.clearAgentTaskContext(sessionId)
        store.replaceWorkingTask(sessionId, message)
        emit(
            move(sessionId, TaskEvent.START_TASK, "Составить план выполнения задачи"),
            trace,
            callback,
        )
        val plan = requireOutput(
            model.complete(promptBuilder.build(store.snapshot(sessionId), message)),
            "Модель не составила план.",
        )
        store.replaceWorkingPlan(sessionId, plan)
        emit(
            move(
                sessionId,
                TaskEvent.PLAN_READY,
                "Утвердить план или прислать замечания",
            ),
            trace,
            callback,
        )
        val response = presentPlan(
            acknowledgement = "Задачу понял так: «${oneLine(message)}».",
            plan = plan,
        )
        store.appendTurn(sessionId, message, response, trace)
        return MessageResponse(response, snapshot(sessionId))
    }

    private fun revisePlan(
        sessionId: String,
        feedback: String,
        trace: MutableList<TaskState>,
        callback: (TaskState) -> Unit,
    ): MessageResponse {
        val task = requiredWorking(sessionId, "agent_task")
        val currentPlan = requiredWorking(sessionId, "agent_plan")
        emit(
            move(sessionId, TaskEvent.REVISE_PLAN, "Пересоставить план по замечаниям"),
            trace,
            callback,
        )
        val revisedPlan = requireOutput(
            model.complete(
                promptBuilder.buildPlanRevision(
                    store.snapshot(sessionId),
                    task,
                    currentPlan,
                    feedback,
                )
            ),
            "Модель не пересоставила план.",
        )
        store.replaceWorkingPlan(sessionId, revisedPlan)
        emit(
            move(
                sessionId,
                TaskEvent.PLAN_READY,
                "Утвердить обновлённый план или прислать замечания",
            ),
            trace,
            callback,
        )
        val response = presentPlan(
            acknowledgement = "Принял замечание к плану: «${oneLine(feedback)}».",
            plan = revisedPlan,
            revised = true,
        )
        store.appendTurn(sessionId, feedback, response, trace)
        return MessageResponse(response, snapshot(sessionId))
    }

    private fun remindAboutExplicitApproval(
        sessionId: String,
        message: String,
    ): MessageResponse {
        val response =
            "Похоже, вы согласны с планом. Я ещё не начал реализацию: нужно отдельное " +
                "подтверждение. Используйте /approve в CLI или кнопку «Утвердить план и выполнить» " +
                "в веб-интерфейсе. До подтверждения задача останется в planning."
        store.appendTurn(sessionId, message, response)
        return MessageResponse(response, snapshot(sessionId))
    }

    private fun executeAndValidate(
        sessionId: String,
        query: String,
        transcriptUserText: String,
        trace: MutableList<TaskState>,
        callback: (TaskState) -> Unit,
    ): MessageResponse {
        var answer = requireOutput(
            model.complete(promptBuilder.build(store.snapshot(sessionId), query)),
            "Модель вернула пустой результат реализации.",
        )
        var validationAttempt = 0
        while (true) {
            emit(
                move(
                    sessionId,
                    TaskEvent.COMPLETE_EXECUTION,
                    "Проверить результат относительно цели задачи",
                ),
                trace,
                callback,
            )
            val feedback = validationFeedback(
                model.complete(promptBuilder.buildValidation(store.snapshot(sessionId), query, answer))
            )
            if (feedback == null) {
                emit(
                    move(sessionId, TaskEvent.PASS_VALIDATION, "Результат проверен и готов"),
                    trace,
                    callback,
                )
                store.appendTurn(sessionId, transcriptUserText, answer, trace)
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
                    callback,
                )
                store.appendTurn(sessionId, transcriptUserText, CLARIFICATION_RESPONSE, trace)
                return MessageResponse(CLARIFICATION_RESPONSE, snapshot(sessionId))
            }

            emit(
                move(
                    sessionId,
                    TaskEvent.REJECT_VALIDATION,
                    "Исправить реализацию по замечаниям проверки",
                ),
                trace,
                callback,
            )
            answer = requireOutput(
                model.complete(
                    promptBuilder.buildRevision(store.snapshot(sessionId), query, answer, feedback)
                ),
                "Модель не вернула исправленный результат.",
            )
            validationAttempt += 1
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

    private fun requiredWorking(sessionId: String, category: String): String =
        store.snapshot(sessionId).working.lastOrNull { it.category == category }?.content
            ?: throw LifecycleException("В рабочей памяти отсутствует $category.")

    private fun presentPlan(
        acknowledgement: String,
        plan: String,
        revised: Boolean = false,
    ): String = buildString {
        appendLine(acknowledgement)
        appendLine()
        appendLine(if (revised) "Обновлённый план:" else "Предлагаемый план:")
        appendLine(plan)
        appendLine()
        append(
            "Я пока не приступаю к реализации. Проверьте план и подтвердите его через " +
                "/approve в CLI или кнопку «Утвердить план и выполнить» в веб-интерфейсе. " +
                "Если нужны изменения, напишите замечания обычным сообщением."
        )
    }

    private fun looksLikeApproval(message: String): Boolean = APPROVAL_MESSAGE.matches(
        message.trim().lowercase()
            .replace(Regex("[,.!?;:]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    )

    private fun oneLine(value: String): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= 240) normalized else normalized.take(237) + "…"
    }

    private fun emit(
        state: TaskState,
        trace: MutableList<TaskState>,
        callback: (TaskState) -> Unit,
    ) {
        trace += state
        callback(state)
    }

    private fun validateMessage(raw: String): String = raw.trim().also {
        require(it.isNotEmpty()) { "Введите непустое сообщение." }
        require(it.length <= 4_000) { "Сообщение не должно превышать 4000 символов." }
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
                .ifEmpty { "Исправить результат согласно проверке." }
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
        val APPROVAL_MESSAGE = Regex(
            "(?:да|ок|окей|согласен|согласна|утверждаю|план подходит|всё хорошо|все хорошо|" +
                "приступай|начинай|можно начинать|делай|да начинай|давай делать|давай начинай|" +
                "ок делай|ок начинай)"
        )
    }
}
