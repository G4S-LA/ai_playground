package groundedrag

import com.google.gson.annotations.SerializedName
import enhancedrag.RankedChunk
import enhancedrag.RetrievalSettings
import ragagent.ControlQuestion

data class GroundedSource(
    val citation: String,
    val source: String,
    val section: String,
    @SerializedName("chunk_id")
    val chunkId: String,
    val similarity: Double,
    val rerankScore: Double,
)

data class VerifiedQuote(
    val citation: String,
    val quote: String,
)

data class AnswerValidation(
    val valid: Boolean,
    val errors: List<String>,
    val attempts: Int,
    val usedFallback: Boolean,
)

data class GroundedAnswer(
    val question: String,
    val searchQuery: String,
    val answer: String,
    val sources: List<GroundedSource>,
    val quotes: List<VerifiedQuote>,
    val candidates: List<RankedChunk>,
    val settings: RetrievalSettings,
    val needsClarification: Boolean,
    val clarificationPrompt: String?,
    val validation: AnswerValidation,
    val rewriteMs: Long,
    val elapsedMs: Long,
)

data class ModelQuote(
    val citation: String = "",
    val quote: String = "",
)

data class ModelSource(
    val citation: String = "",
    val source: String = "",
    val section: String = "",
    @SerializedName("chunk_id")
    val chunkId: String = "",
)

data class ModelGroundedPayload(
    val answer: String = "",
    val sources: List<ModelSource> = emptyList(),
    val quotes: List<ModelQuote> = emptyList(),
)

data class PayloadValidation(
    val valid: Boolean,
    val errors: List<String>,
    val sources: List<GroundedSource>,
    val quotes: List<VerifiedQuote>,
)

data class SupportAssessment(
    val supported: Boolean,
    val reason: String,
    val method: String,
)

data class GroundedQuestionEvaluation(
    val control: ControlQuestion,
    val result: GroundedAnswer,
    val hasSources: Boolean,
    val hasQuotes: Boolean,
    val quotesAreExact: Boolean,
    val citationsAreConsistent: Boolean,
    val expectedSourceRecall: Double,
    val semanticSupport: SupportAssessment,
)

data class GroundedEvaluationSummary(
    val sourcePresenceRate: Double,
    val quotePresenceRate: Double,
    val exactQuoteRate: Double,
    val citationConsistencyRate: Double,
    val semanticSupportRate: Double,
    val expectedSourceRecall: Double,
    val unknownRate: Double,
)

data class GroundedEvaluationSnapshot(
    val id: String,
    val status: String,
    val completed: Int,
    val total: Int,
    val currentQuestion: String?,
    val results: List<GroundedQuestionEvaluation>,
    val summary: GroundedEvaluationSummary?,
    val error: String?,
)
