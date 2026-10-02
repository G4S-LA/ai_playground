package docindex

import kotlin.time.TimeSource

class IndexingService(
    private val loader: DocumentLoader,
    private val embeddings: EmbeddingProvider,
    private val index: SqliteVectorIndex,
) {
    val embeddingDescription: String get() = embeddings.description

    suspend fun build(
        strategy: ChunkStrategy,
        fixedSize: Int = 1_500,
        overlap: Int = 250,
        structuredMaxSize: Int = 2_000,
    ): IndexBuildResult {
        val started = TimeSource.Monotonic.markNow()
        val documents = loader.loadAll()
        require(documents.isNotEmpty()) {
            "Документы не найдены. Загрузите .md, .txt, .kt, .java или .pdf файлы."
        }
        val chunker: DocumentChunker = when (strategy) {
            ChunkStrategy.FIXED -> FixedSizeChunker(fixedSize, overlap)
            ChunkStrategy.STRUCTURED -> StructuredChunker(structuredMaxSize, overlap.coerceAtMost(structuredMaxSize - 1))
        }
        val chunks = documents.flatMap(chunker::chunk)
        require(chunks.isNotEmpty()) { "В документах нет текста для индексации." }

        val vectors = chunks.chunked(32).flatMap { batch -> embeddings.embed(batch.map { it.text }) }
        check(vectors.size == chunks.size) { "Количество эмбеддингов не совпадает с количеством чанков." }
        val dimensions = vectors.first().size
        check(dimensions > 0 && vectors.all { it.size == dimensions }) { "Модель вернула эмбеддинги разной размерности." }
        check(vectors.all { vector -> vector.all(Float::isFinite) }) { "Модель вернула некорректные значения." }

        index.replace(strategy, chunks.zip(vectors) { chunk, vector -> IndexedChunk(chunk, vector) })
        return IndexBuildResult(
            strategy = strategy.wireName,
            documents = documents.map { it.source }.distinct().size,
            chunks = chunks.size,
            embeddingDimensions = dimensions,
            elapsedMs = started.elapsedNow().inWholeMilliseconds,
        )
    }

    suspend fun search(query: String, strategy: ChunkStrategy, topK: Int = 5): List<SearchHit> {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotEmpty()) { "Введите поисковый запрос." }
        require(cleanQuery.length <= 2_000) { "Запрос не должен превышать 2000 символов." }
        val vector = embeddings.embed(listOf(cleanQuery)).single()
        return index.search(strategy, vector, topK)
    }

    suspend fun compare(query: String, topK: Int = 5): ComparisonResult {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotEmpty()) { "Введите поисковый запрос." }
        require(cleanQuery.length <= 2_000) { "Запрос не должен превышать 2000 символов." }
        val vector = embeddings.embed(listOf(cleanQuery)).single()
        return ComparisonResult(
            query = cleanQuery,
            fixed = index.search(ChunkStrategy.FIXED, vector, topK),
            structured = index.search(ChunkStrategy.STRUCTURED, vector, topK),
        )
    }

    fun stats(): List<StrategyStats> = index.stats()
}
