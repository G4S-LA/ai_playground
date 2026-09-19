package memory

class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MemoryAgent(
    private val store: FileMemoryStore,
    private val model: LanguageModel,
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
        val answer = model.complete(promptBuilder.build(before, message)).trim()
        require(answer.isNotEmpty()) { "Модель вернула пустой ответ." }
        store.appendTurn(sessionId, message, answer)
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

    private fun MemorySnapshot.withModel() = AgentSnapshot(session, working, longTerm, modelName)
}
