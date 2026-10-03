package enhancedrag

import docindex.SearchHit
import java.util.Locale

class RelevancePipeline {
    fun baseline(hits: List<SearchHit>, question: String, finalK: Int): List<RankedChunk> = hits.mapIndexed { index, hit ->
        RankedChunk.baseline(hit, lexicalScore(question, hit), selected = index < finalK)
    }

    fun enhanced(
        hits: List<SearchHit>,
        question: String,
        threshold: Double,
        finalK: Int,
    ): List<RankedChunk> {
        val scored = hits.map { hit ->
            val lexical = lexicalScore(question, hit)
            RankedChunk(
                chunkId = hit.chunkId,
                source = hit.source,
                title = hit.title,
                section = hit.section,
                text = hit.text,
                similarity = hit.score.toDouble(),
                lexicalScore = lexical,
                rerankScore = hit.score.toDouble() * SIMILARITY_WEIGHT + lexical * LEXICAL_WEIGHT,
                passedThreshold = hit.score >= threshold,
                selected = false,
            )
        }.sortedWith(compareByDescending<RankedChunk> { it.passedThreshold }.thenByDescending { it.rerankScore })

        val selectedIds = scored.asSequence()
            .filter(RankedChunk::passedThreshold)
            .take(finalK)
            .map(RankedChunk::chunkId)
            .toSet()
        return scored.map { it.copy(selected = it.chunkId in selectedIds) }
    }

    fun lexicalScore(query: String, hit: SearchHit): Double {
        val queryTerms = terms(query)
        if (queryTerms.isEmpty()) return 0.0
        val documentTerms = terms("${hit.title} ${hit.section} ${hit.text}")
        return queryTerms.count(documentTerms::contains).toDouble() / queryTerms.size
    }

    private fun terms(value: String): Set<String> = TOKEN.findAll(value.lowercase(Locale.ROOT).replace('ё', 'е'))
        .map(MatchResult::value)
        .filter { it.length > 2 && it !in STOP_WORDS }
        .toSet()

    private companion object {
        const val SIMILARITY_WEIGHT = 0.8
        const val LEXICAL_WEIGHT = 0.2
        val TOKEN = Regex("[\\p{L}\\p{N}_-]+")
        val STOP_WORDS = setOf(
            "как", "что", "это", "для", "или", "при", "чем", "какие", "какой", "какую", "почему", "зачем",
            "его", "она", "они", "под", "над", "без", "где", "когда", "который", "согласно", "книга", "книги",
        )
    }
}
