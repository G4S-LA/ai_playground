package docindex

enum class ChunkStrategy(val wireName: String) {
    FIXED("fixed"),
    STRUCTURED("structured");

    companion object {
        fun parse(raw: String): ChunkStrategy = entries.firstOrNull {
            it.wireName.equals(raw.trim(), ignoreCase = true)
        } ?: throw IllegalArgumentException("Неизвестная стратегия chunking: $raw")
    }
}

enum class ContentType(val wireName: String) {
    MARKDOWN("markdown"),
    CODE("code"),
    PDF("pdf"),
    TEXT("text"),
}

data class SourceDocument(
    val source: String,
    val title: String,
    val contentType: ContentType,
    val text: String,
    val baseSection: String = "Документ",
    val page: Int? = null,
)

data class ChunkDraft(
    val chunkId: String,
    val strategy: ChunkStrategy,
    val source: String,
    val title: String,
    val section: String,
    val contentType: ContentType,
    val chunkIndex: Int,
    val startPosition: Int,
    val endPosition: Int,
    val tokenCount: Int,
    val page: Int?,
    val text: String,
)

data class IndexedChunk(
    val chunk: ChunkDraft,
    val embedding: FloatArray,
)

data class SearchHit(
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String,
    val contentType: String,
    val chunkIndex: Int,
    val tokenCount: Int,
    val page: Int?,
    val text: String,
    val score: Float,
)

data class StrategyStats(
    val strategy: String,
    val documents: Int,
    val chunks: Int,
    val averageCharacters: Double,
    val averageTokens: Double,
)

data class IndexBuildResult(
    val strategy: String,
    val documents: Int,
    val chunks: Int,
    val embeddingDimensions: Int,
    val elapsedMs: Long,
)

data class ComparisonResult(
    val query: String,
    val fixed: List<SearchHit>,
    val structured: List<SearchHit>,
)
