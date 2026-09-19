package memory

data class InvariantItem(
    val id: String,
    val category: String,
    val content: String,
    val createdAt: String,
)

data class InvariantDocument(val items: List<InvariantItem> = emptyList())

data class InvariantCheck(
    val invariantId: String,
    val status: String,
    val explanation: String,
)

data class ComplianceReport(
    val status: String,
    val checks: List<InvariantCheck>,
    val summary: String,
) {
    val allowsResponse: Boolean get() = status == COMPLIANT

    companion object {
        const val COMPLIANT = "compliant"
        const val VIOLATION = "violation"
        const val UNVERIFIABLE = "unverifiable"

        fun noInvariants() = ComplianceReport(
            status = COMPLIANT,
            checks = emptyList(),
            summary = "Активных инвариантов нет.",
        )
    }
}

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: String,
    val category: String? = null,
    val compliance: ComplianceReport? = null,
)

data class MemoryItem(
    val id: String,
    val category: String,
    val content: String,
    val createdAt: String,
)

data class SessionDocument(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val messages: List<ChatMessage> = emptyList(),
)

data class MemoryDocument(val items: List<MemoryItem> = emptyList())

data class SessionSummary(
    val id: String,
    val title: String,
    val updatedAt: String,
    val messageCount: Int,
)

data class MemorySnapshot(
    val session: SessionDocument,
    val working: List<MemoryItem>,
    val longTerm: List<MemoryItem>,
    val invariants: List<InvariantItem>,
)

data class AgentSnapshot(
    val session: SessionDocument,
    val working: List<MemoryItem>,
    val longTerm: List<MemoryItem>,
    val invariants: List<InvariantItem>,
    val model: String,
)

enum class MemoryLayer(val wireName: String) {
    SHORT_TERM("short_term"),
    WORKING("working"),
    LONG_TERM("long_term");

    companion object {
        fun fromWireName(value: String): MemoryLayer = entries.firstOrNull {
            it.wireName == value.lowercase()
        } ?: throw IllegalArgumentException(
            "Неизвестный слой памяти '$value'. Допустимо: short_term, working, long_term."
        )
    }
}

object MemoryCategories {
    private val values = mapOf(
        MemoryLayer.SHORT_TERM to setOf("dialogue_note"),
        MemoryLayer.WORKING to setOf("goal", "context", "constraint", "note"),
        MemoryLayer.LONG_TERM to setOf("profile", "decision", "knowledge"),
    )

    fun validate(layer: MemoryLayer, category: String): String {
        val normalized = category.trim().lowercase()
        require(normalized in values.getValue(layer)) {
            "Категория '$category' не подходит для ${layer.wireName}. " +
                "Допустимо: ${values.getValue(layer).joinToString()}."
        }
        return normalized
    }

    fun asMap(): Map<String, List<String>> = values.mapKeys { it.key.wireName }
        .mapValues { it.value.toList() }
}

object InvariantCategories {
    private val values = setOf("architecture", "technical_decision", "stack_constraint", "business_rule")

    fun validate(category: String): String {
        val normalized = category.trim().lowercase()
        require(normalized in values) {
            "Неизвестная категория инварианта '$category'. Допустимо: ${values.joinToString()}."
        }
        return normalized
    }

    fun all(): List<String> = values.toList()
}

data class CreateSessionResponse(val session: SessionSummary)
data class SessionsResponse(val sessions: List<SessionSummary>)
data class SnapshotResponse(val snapshot: AgentSnapshot)
data class MessageRequest(val message: String = "")
data class MessageResponse(
    val answer: String,
    val compliance: ComplianceReport,
    val snapshot: AgentSnapshot,
)
data class RememberRequest(
    val layer: String = "",
    val category: String = "",
    val content: String = "",
)
data class RememberResponse(val memory: MemoryItem, val snapshot: AgentSnapshot)
data class InvariantRequest(val category: String = "", val content: String = "")
data class InvariantResponse(val invariant: InvariantItem, val snapshot: AgentSnapshot)
data class CategoriesResponse(
    val memory: Map<String, List<String>>,
    val invariants: List<String>,
)
data class ErrorResponse(val error: String)
