package memory

class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MemoryAgent(
    private val store: FileMemoryStore,
    private val model: LanguageModel,
    private val guard: InvariantGuard,
    systemPrompt: String,
    private val modelName: String,
) {
    private val promptBuilder = PromptBuilder(systemPrompt)

    fun listSessions(): List<SessionSummary> = store.listSessions()
    fun createSession(): SessionSummary = store.createSession()
    fun snapshot(sessionId: String): AgentSnapshot = store.snapshot(sessionId).withModel()

    @Synchronized
    fun reply(sessionId: String, rawMessage: String): MessageResponse {
        val message = rawMessage.trim()
        require(message.isNotEmpty()) { "Введите непустое сообщение." }
        require(message.length <= 4_000) { "Сообщение не должно превышать 4000 символов." }
        val before = store.snapshot(sessionId)
        val draft = model.complete(promptBuilder.build(before, message)).trim()
        require(draft.isNotEmpty()) { "Модель вернула пустой ответ." }
        val compliance = guard.evaluate(message, draft, before.invariants)
        val answer = if (compliance.allowsResponse) draft else refusal(compliance, before.invariants)
        store.appendTurn(sessionId, message, answer, compliance)
        return MessageResponse(answer, compliance, snapshot(sessionId))
    }

    fun remember(sessionId: String, layerName: String, category: String, content: String): RememberResponse {
        val item = store.remember(sessionId, MemoryLayer.fromWireName(layerName), category, content)
        return RememberResponse(item, snapshot(sessionId))
    }

    fun addInvariant(sessionId: String, category: String, content: String): InvariantResponse {
        val item = store.addInvariant(category, content)
        return InvariantResponse(item, snapshot(sessionId))
    }

    fun removeInvariant(sessionId: String, invariantId: String): AgentSnapshot {
        store.removeInvariant(invariantId)
        return snapshot(sessionId)
    }

    fun forget(sessionId: String, layerName: String, memoryId: String): AgentSnapshot {
        store.forget(sessionId, MemoryLayer.fromWireName(layerName), memoryId)
        return snapshot(sessionId)
    }

    fun deleteSession(sessionId: String) = store.deleteSession(sessionId)

    fun promptPreview(sessionId: String, query: String): List<PromptMessage> =
        promptBuilder.build(store.snapshot(sessionId), query)

    private fun refusal(report: ComplianceReport, invariants: List<InvariantItem>): String {
        if (report.status == ComplianceReport.UNVERIFIABLE) {
            return "Не могу безопасно предложить решение: проверка соблюдения инвариантов не завершилась корректно."
        }
        val failed = report.checks.firstOrNull { it.status == "violated" }
        val invariant = invariants.firstOrNull { it.id == failed?.invariantId }
        return buildString {
            append("Не могу предложить это решение: оно нарушает обязательный инвариант")
            if (invariant != null) append(" «${invariant.content}»")
            append('.')
            if (!failed?.explanation.isNullOrBlank()) append(" ${failed.explanation}")
        }
    }

    private fun MemorySnapshot.withModel() = AgentSnapshot(session, working, longTerm, invariants, modelName)
}
