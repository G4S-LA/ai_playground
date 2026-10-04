package memorychat

import groundedrag.GroundedSource
import groundedrag.VerifiedQuote

data class MemoryTerm(
    val term: String,
    val meaning: String,
)

data class TaskMemory(
    val goal: String? = null,
    val clarifiedFacts: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val terms: List<MemoryTerm> = emptyList(),
    val revision: Int = 0,
    val updateMethod: String = "initial",
)

data class MemoryPlan(
    val memory: TaskMemory,
    val standaloneQuestion: String,
)

data class StoredChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val sources: List<GroundedSource> = emptyList(),
    val quotes: List<VerifiedQuote> = emptyList(),
    val needsClarification: Boolean = false,
    val createdAt: String,
)

data class ChatSnapshot(
    val id: String,
    val messages: List<StoredChatMessage>,
    val memory: TaskMemory,
    val createdAt: String,
    val updatedAt: String,
)

data class ChatReply(
    val session: ChatSnapshot,
    val message: StoredChatMessage,
)
