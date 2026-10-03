package ragagent

import docindex.SearchHit

enum class AnswerMode(val wireName: String) {
    WITHOUT_RAG("without_rag"),
    WITH_RAG("with_rag"),
}

data class RagSource(
    val citation: String,
    val chunkId: String,
    val source: String,
    val section: String,
    val score: Float,
    val text: String,
) {
    companion object {
        fun from(index: Int, hit: SearchHit): RagSource = RagSource(
            citation = "S${index + 1}",
            chunkId = hit.chunkId,
            source = hit.source,
            section = hit.section,
            score = hit.score,
            text = hit.text,
        )
    }
}

data class AgentAnswer(
    val mode: String,
    val question: String,
    val answer: String,
    val sources: List<RagSource>,
    val elapsedMs: Long,
)

data class AnswerComparison(
    val question: String,
    val withoutRag: AgentAnswer,
    val withRag: AgentAnswer,
)

data class ControlQuestion(
    val id: Int = 0,
    val question: String = "",
    val expectation: String = "",
    val expectedSources: List<String> = emptyList(),
    val expectedSections: List<String> = emptyList(),
    val requiredTerms: List<String> = emptyList(),
)

data class QualityAssessment(
    val conceptCoverage: Double,
    val matchedTerms: List<String>,
    val missingTerms: List<String>,
    val sourceRecall: Double?,
    val matchedSources: List<String>,
    val hasCitations: Boolean,
)

data class EvaluatedAnswer(
    val result: AgentAnswer,
    val quality: QualityAssessment,
)

data class QuestionEvaluation(
    val control: ControlQuestion,
    val withoutRag: EvaluatedAnswer,
    val withRag: EvaluatedAnswer,
)

data class EvaluationSummary(
    val withoutRagConceptCoverage: Double,
    val withRagConceptCoverage: Double,
    val ragSourceRecall: Double,
    val ragCitationRate: Double,
)

data class EvaluationRunSnapshot(
    val id: String,
    val status: String,
    val completed: Int,
    val total: Int,
    val currentQuestion: String?,
    val results: List<QuestionEvaluation>,
    val summary: EvaluationSummary?,
    val error: String?,
)
