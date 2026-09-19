package memory

class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MemoryAgent(
    private val store: FileMemoryStore,
    private val model: LanguageModel,
    systemPrompt: String,
    private val modelName: String,
    private val planningPolicy: PlanningPolicy = RequestComplexityPolicy(),
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
        store.clearWorkingPlan(sessionId)

        val trace = mutableListOf<TaskState>()
        var step = 1
        if (planningPolicy.needsPlanning(message)) {
            emit(
                store.startTask(sessionId, TaskStage.PLANNING, "Составить план выполнения запроса"),
                trace,
                onStateChange,
            )
            val plan = requireOutput(
                model.complete(promptBuilder.build(store.snapshot(sessionId), message)),
                "Модель не составила план.",
            )
            store.replaceWorkingPlan(sessionId, plan)
            step += 1
            emit(
                store.transitionTaskState(
                    sessionId,
                    TaskStage.EXECUTION.wireName,
                    step,
                    "Выполнить составленный план",
                ),
                trace,
                onStateChange,
            )
        } else {
            emit(
                store.startTask(sessionId, TaskStage.EXECUTION, "Выполнить запрос пользователя"),
                trace,
                onStateChange,
            )
        }

        var answer = requireOutput(
            model.complete(promptBuilder.build(store.snapshot(sessionId), message)),
            "Модель вернула пустой ответ.",
        )
        var accepted = false
        var needsClarification = false
        var validationAttempt = 0
        while (!accepted && validationAttempt < MAX_VALIDATION_ATTEMPTS) {
            step += 1
            emit(
                store.transitionTaskState(
                    sessionId,
                    TaskStage.VALIDATION.wireName,
                    step,
                    "Проверить полноту и корректность ответа",
                ),
                trace,
                onStateChange,
            )
            val feedback = validationFeedback(
                model.complete(promptBuilder.buildValidation(store.snapshot(sessionId), message, answer))
            )
            if (feedback == null) {
                accepted = true
                continue
            }
            if (validationAttempt == MAX_VALIDATION_ATTEMPTS - 1) {
                answer = CLARIFICATION_RESPONSE
                needsClarification = true
                accepted = true
                continue
            }
            step += 1
            emit(
                store.transitionTaskState(
                    sessionId,
                    TaskStage.EXECUTION.wireName,
                    step,
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
        check(accepted) { "Проверка ответа не завершилась." }

        step += 1
        emit(
            store.transitionTaskState(
                sessionId,
                TaskStage.DONE.wireName,
                step,
                if (needsClarification) "Подготовлен запрос на уточнение" else "Ответ проверен и готов",
            ),
            trace,
            onStateChange,
        )
        store.appendTurn(sessionId, message, answer, trace)
        return MessageResponse(answer, snapshot(sessionId))
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
            "Не удалось подготовить надёжный ответ. Пожалуйста, уточните, что именно нужно сделать."
    }
}
