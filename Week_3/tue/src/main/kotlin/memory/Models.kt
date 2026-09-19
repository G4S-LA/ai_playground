package memory

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: String,
    val category: String? = null,
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
    val profileId: String? = UserProfiles.DEFAULT_ID,
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
    val profile: UserProfile,
)

data class AgentSnapshot(
    val session: SessionDocument,
    val working: List<MemoryItem>,
    val longTerm: List<MemoryItem>,
    val profile: UserProfile,
    val model: String,
)

data class UserProfile(
    val id: String,
    val name: String,
    val style: String,
    val format: String,
    val constraints: List<String>,
    val createdAt: String,
    val updatedAt: String,
)

object UserProfiles {
    const val DEFAULT_ID = "default"
}

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

data class CreateSessionRequest(val profileId: String? = UserProfiles.DEFAULT_ID)
data class CreateSessionResponse(val session: SessionSummary)
data class SessionsResponse(val sessions: List<SessionSummary>)
data class SnapshotResponse(val snapshot: AgentSnapshot)
data class MessageRequest(val message: String? = "")
data class MessageResponse(val answer: String, val snapshot: AgentSnapshot)
data class RememberRequest(
    val layer: String? = "",
    val category: String? = "",
    val content: String? = "",
)
data class RememberResponse(val memory: MemoryItem, val snapshot: AgentSnapshot)
data class ProfileRequest(
    val name: String? = "",
    val style: String? = "",
    val format: String? = "",
    val constraints: List<String?>? = emptyList(),
)
data class SelectProfileRequest(val profileId: String? = "")
data class ProfileResponse(val profile: UserProfile)
data class ProfilesResponse(val profiles: List<UserProfile>)
data class CategoriesResponse(val categories: Map<String, List<String>>)
data class ErrorResponse(val error: String)
