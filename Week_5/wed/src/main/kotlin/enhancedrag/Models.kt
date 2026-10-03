package enhancedrag

import docindex.SearchHit
import ragagent.QualityAssessment
import ragagent.RagSource

enum class RetrievalMode(val wireName: String) {
    BASELINE("baseline"),
    ENHANCED("enhanced"),
}

data class RetrievalSettings(
    val candidateK: Int = 12,
    val finalK: Int = 5,
    val similarityThreshold: Double = 0.35,
) {
    fun validated(): RetrievalSettings = apply {
        require(candidateK in 1..30) { "candidateK должен быть от 1 до 30." }
        require(finalK in 1..candidateK) { "finalK должен быть от 1 до candidateK." }
        require(similarityThreshold in -1.0..1.0) { "Порог similarity должен быть от -1 до 1." }
    }
}

data class RankedChunk(
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String,
    val text: String,
    val similarity: Double,
    val lexicalScore: Double,
    val rerankScore: Double,
    val passedThreshold: Boolean,
    val selected: Boolean,
) {
    fun toSource(index: Int): RagSource = RagSource(
        citation = "S${index + 1}",
        chunkId = chunkId,
        source = source,
        section = section,
        score = rerankScore.toFloat(),
        text = text,
    )

    companion object {
        fun baseline(hit: SearchHit, lexicalScore: Double, selected: Boolean): RankedChunk = RankedChunk(
            chunkId = hit.chunkId,
            source = hit.source,
            title = hit.title,
            section = hit.section,
            text = hit.text,
            similarity = hit.score.toDouble(),
            lexicalScore = lexicalScore,
            rerankScore = hit.score.toDouble(),
            passedThreshold = true,
            selected = selected,
        )
    }
}

data class RetrievalAnswer(
    val mode: String,
    val question: String,
    val searchQuery: String,
    val answer: String,
    val candidates: List<RankedChunk>,
    val sources: List<RagSource>,
    val settings: RetrievalSettings,
    val rewriteMs: Long,
    val elapsedMs: Long,
)

data class RetrievalComparison(
    val question: String,
    val baseline: RetrievalAnswer,
    val enhanced: RetrievalAnswer,
)

data class EvaluatedRetrievalAnswer(
    val result: RetrievalAnswer,
    val quality: QualityAssessment,
)

data class RetrievalQuestionEvaluation(
    val control: ragagent.ControlQuestion,
    val baseline: EvaluatedRetrievalAnswer,
    val enhanced: EvaluatedRetrievalAnswer,
)

data class RetrievalEvaluationSummary(
    val baselineConceptCoverage: Double,
    val enhancedConceptCoverage: Double,
    val baselineSourceRecall: Double,
    val enhancedSourceRecall: Double,
    val baselineCitationRate: Double,
    val enhancedCitationRate: Double,
    val enhancedCandidateRetention: Double,
)

data class RetrievalEvaluationSnapshot(
    val id: String,
    val status: String,
    val completed: Int,
    val total: Int,
    val currentQuestion: String?,
    val results: List<RetrievalQuestionEvaluation>,
    val summary: RetrievalEvaluationSummary?,
    val error: String?,
)
