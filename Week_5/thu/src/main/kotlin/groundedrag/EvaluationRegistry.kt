package groundedrag

import enhancedrag.RetrievalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ragagent.ControlQuestion
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GroundedEvaluationRegistry(
    private val service: GroundedRagService,
    private val judge: SemanticSupportJudge,
    private val controls: List<ControlQuestion>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val runs = ConcurrentHashMap<String, MutableGroundedEvaluation>()

    fun start(settings: RetrievalSettings): GroundedEvaluationSnapshot {
        settings.validated()
        val run = MutableGroundedEvaluation(UUID.randomUUID().toString(), controls.size)
        runs[run.id] = run
        scope.launch {
            try {
                controls.forEach { control ->
                    run.begin(control.question)
                    val answer = service.answer(control.question, settings)
                    val assessment = judge.assess(answer)
                    run.add(evaluate(control, answer, assessment))
                }
                run.complete()
            } catch (error: Exception) {
                run.fail(error.message ?: "Неизвестная ошибка оценки.")
            }
        }
        return run.snapshot()
    }

    fun snapshot(id: String): GroundedEvaluationSnapshot? = runs[id]?.snapshot()

    private fun evaluate(
        control: ControlQuestion,
        result: GroundedAnswer,
        semanticSupport: SupportAssessment,
    ): GroundedQuestionEvaluation {
        val candidateText = result.candidates.associate { it.chunkId to it.text }
        val quotesExact = result.quotes.all { quote ->
            val source = result.sources.firstOrNull { it.citation == quote.citation }
            source != null && candidateText[source.chunkId]?.contains(quote.quote) == true
        } && result.quotes.isNotEmpty()
        val markers = CITATION.findAll(result.answer).map { it.groupValues[1] }.toSet()
        val sourceIds = result.sources.map { it.citation }.toSet()
        val quoteIds = result.quotes.map { it.citation }.toSet()
        val matchedSources = control.expectedSources.count { expected ->
            result.sources.any { source -> source.source.endsWith(expected) }
        }
        return GroundedQuestionEvaluation(
            control = control,
            result = result,
            hasSources = result.sources.isNotEmpty(),
            hasQuotes = result.quotes.isNotEmpty(),
            quotesAreExact = quotesExact,
            citationsAreConsistent = sourceIds.isNotEmpty() && markers == sourceIds && quoteIds == sourceIds,
            expectedSourceRecall = if (control.expectedSources.isEmpty()) 1.0 else {
                matchedSources.toDouble() / control.expectedSources.size
            },
            semanticSupport = semanticSupport,
        )
    }

    private companion object {
        val CITATION = Regex("\\[(S\\d+)\\]")
    }
}

private class MutableGroundedEvaluation(val id: String, private val total: Int) {
    private val results = mutableListOf<GroundedQuestionEvaluation>()
    private var status = "running"
    private var currentQuestion: String? = null
    private var error: String? = null

    @Synchronized fun begin(question: String) { currentQuestion = question }
    @Synchronized fun add(result: GroundedQuestionEvaluation) { results += result }
    @Synchronized fun complete() { status = "completed"; currentQuestion = null }
    @Synchronized fun fail(message: String) { status = "failed"; error = message; currentQuestion = null }

    @Synchronized
    fun snapshot(): GroundedEvaluationSnapshot = GroundedEvaluationSnapshot(
        id = id,
        status = status,
        completed = results.size,
        total = total,
        currentQuestion = currentQuestion,
        results = results.toList(),
        summary = if (status == "completed") summary() else null,
        error = error,
    )

    private fun summary(): GroundedEvaluationSummary {
        if (results.isEmpty()) return GroundedEvaluationSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        fun rate(predicate: (GroundedQuestionEvaluation) -> Boolean) = results.count(predicate).toDouble() / results.size
        return GroundedEvaluationSummary(
            sourcePresenceRate = rate(GroundedQuestionEvaluation::hasSources),
            quotePresenceRate = rate(GroundedQuestionEvaluation::hasQuotes),
            exactQuoteRate = rate(GroundedQuestionEvaluation::quotesAreExact),
            citationConsistencyRate = rate(GroundedQuestionEvaluation::citationsAreConsistent),
            semanticSupportRate = rate { it.semanticSupport.supported },
            expectedSourceRecall = results.map { it.expectedSourceRecall }.average(),
            unknownRate = rate { it.result.needsClarification },
        )
    }
}
