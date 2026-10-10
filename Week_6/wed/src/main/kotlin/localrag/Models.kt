package localrag

import docindex.SearchHit

data class RagSource(
    val citation: String,
    val chunkId: String,
    val source: String,
    val section: String,
    val score: Float,
    val text: String,
) {
    companion object {
        fun from(index: Int, hit: SearchHit) = RagSource(
            citation = "S${index + 1}",
            chunkId = hit.chunkId,
            source = hit.source,
            section = hit.section,
            score = hit.score,
            text = hit.text,
        )
    }
}

data class LocalRagAnswer(
    val question: String,
    val answer: String,
    val sources: List<RagSource>,
    val retrievalMs: Long,
    val generationMs: Long,
    val totalMs: Long,
)

data class OllamaStatus(
    val endpoint: String,
    val reachable: Boolean,
    val availableModels: List<String> = emptyList(),
    val error: String? = null,
) {
    fun hasModel(model: String): Boolean = availableModels.any {
        it == model || it.removeSuffix(":latest") == model.removeSuffix(":latest")
    }
}
