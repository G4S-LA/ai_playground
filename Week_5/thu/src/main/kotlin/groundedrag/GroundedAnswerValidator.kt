package groundedrag

import enhancedrag.RankedChunk

class GroundedAnswerValidator {
    fun validate(payload: ModelGroundedPayload, available: List<RankedChunk>): PayloadValidation {
        val errors = mutableListOf<String>()
        val availableByCitation = available.mapIndexed { index, chunk -> "S${index + 1}" to chunk }.toMap()
        val requestedSources = payload.sources.map { it.citation.trim() }.filter(String::isNotEmpty)
        val answerMarkers = CITATION.findAll(payload.answer).map { it.groupValues[1] }.toSet()
        val quoteMarkers = payload.quotes.map { it.citation.trim() }.filter(String::isNotEmpty).toSet()

        if (payload.answer.isBlank()) errors += "Поле answer пусто."
        if (requestedSources.isEmpty()) errors += "Список sources пуст."
        if (payload.quotes.isEmpty()) errors += "Список quotes пуст."
        if (requestedSources.distinct().size != requestedSources.size) errors += "В sources есть дубликаты."

        val unknownSources = requestedSources.filterNot(availableByCitation::containsKey)
        if (unknownSources.isNotEmpty()) errors += "Неизвестные sources: ${unknownSources.joinToString()}."
        payload.sources.forEach { modelSource ->
            val chunk = availableByCitation[modelSource.citation.trim()]
            if (chunk != null && (
                    modelSource.source != chunk.source ||
                        modelSource.section != chunk.section ||
                        modelSource.chunkId != chunk.chunkId
                    )
            ) {
                errors += "Метаданные ${modelSource.citation} не совпадают с найденным чанком."
            }
        }
        val unknownQuotes = quoteMarkers.filterNot(availableByCitation::containsKey)
        if (unknownQuotes.isNotEmpty()) errors += "Цитаты ссылаются на неизвестные источники: ${unknownQuotes.joinToString()}."
        val unknownAnswerMarkers = answerMarkers.filterNot(availableByCitation::containsKey)
        if (unknownAnswerMarkers.isNotEmpty()) errors += "В answer есть неизвестные ссылки: ${unknownAnswerMarkers.joinToString()}."
        if (answerMarkers != requestedSources.toSet()) errors += "Ссылки в answer должны совпадать со списком sources."
        if (quoteMarkers != requestedSources.toSet()) errors += "Для каждого source нужна хотя бы одна quote."

        payload.quotes.forEachIndexed { index, quote ->
            val chunk = availableByCitation[quote.citation.trim()]
            if (quote.quote.isBlank()) {
                errors += "Цитата ${index + 1} пуста."
            } else if (chunk != null && quote.quote !in chunk.text) {
                errors += "Цитата ${index + 1} не является дословным фрагментом ${quote.citation}."
            }
        }

        val verifiedSources = requestedSources.distinct().mapNotNull { citation ->
            availableByCitation[citation]?.let { chunk ->
                GroundedSource(
                    citation = citation,
                    source = chunk.source,
                    section = chunk.section,
                    chunkId = chunk.chunkId,
                    similarity = chunk.similarity,
                    rerankScore = chunk.rerankScore,
                )
            }
        }
        val verifiedQuotes = payload.quotes.mapNotNull { quote ->
            val citation = quote.citation.trim()
            availableByCitation[citation]?.takeIf { quote.quote.isNotBlank() && quote.quote in it.text }
                ?.let { VerifiedQuote(citation, quote.quote) }
        }
        return PayloadValidation(errors.isEmpty(), errors.distinct(), verifiedSources, verifiedQuotes)
    }

    private companion object {
        val CITATION = Regex("\\[(S\\d+)\\]")
    }
}
