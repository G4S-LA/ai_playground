package enhancedrag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ragagent.AgentAnswer
import ragagent.AnswerMode
import ragagent.ControlQuestion
import ragagent.QualityEvaluator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RetrievalEvaluationRegistry(
    private val service: EnhancedRagService,
    private val controls: List<ControlQuestion>,
    private val evaluator: QualityEvaluator = QualityEvaluator(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val runs = ConcurrentHashMap<String, MutableRetrievalEvaluation>()

    fun start(settings: RetrievalSettings): RetrievalEvaluationSnapshot {
        settings.validated()
        val run = MutableRetrievalEvaluation(UUID.randomUUID().toString(), controls.size)
        runs[run.id] = run
        scope.launch {
            try {
                controls.forEach { control ->
                    run.begin(control.question)
                    val comparison = service.compare(control.question, settings)
                    run.add(
                        RetrievalQuestionEvaluation(
                            control = control,
                            baseline = comparison.baseline.evaluated(control),
                            enhanced = comparison.enhanced.evaluated(control),
                        ),
                    )
                }
                run.complete()
            } catch (error: Exception) {
                run.fail(error.message ?: "Неизвестная ошибка оценки.")
            }
        }
        return run.snapshot()
    }

    fun snapshot(id: String): RetrievalEvaluationSnapshot? = runs[id]?.snapshot()

    private fun RetrievalAnswer.evaluated(control: ControlQuestion): EvaluatedRetrievalAnswer {
        val compatible = AgentAnswer(
            mode = AnswerMode.WITH_RAG.wireName,
            question = question,
            answer = answer,
            sources = sources,
            elapsedMs = elapsedMs,
        )
        return EvaluatedRetrievalAnswer(this, evaluator.evaluate(compatible, control))
    }
}

private class MutableRetrievalEvaluation(val id: String, private val total: Int) {
    private val results = mutableListOf<RetrievalQuestionEvaluation>()
    private var status = "running"
    private var currentQuestion: String? = null
    private var error: String? = null

    @Synchronized fun begin(question: String) { currentQuestion = question }
    @Synchronized fun add(result: RetrievalQuestionEvaluation) { results += result }
    @Synchronized fun complete() { status = "completed"; currentQuestion = null }
    @Synchronized fun fail(message: String) { status = "failed"; error = message; currentQuestion = null }

    @Synchronized
    fun snapshot(): RetrievalEvaluationSnapshot = RetrievalEvaluationSnapshot(
        id = id,
        status = status,
        completed = results.size,
        total = total,
        currentQuestion = currentQuestion,
        results = results.toList(),
        summary = if (status == "completed") summary() else null,
        error = error,
    )

    private fun summary(): RetrievalEvaluationSummary {
        if (results.isEmpty()) return RetrievalEvaluationSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        return RetrievalEvaluationSummary(
            baselineConceptCoverage = results.map { it.baseline.quality.conceptCoverage }.average(),
            enhancedConceptCoverage = results.map { it.enhanced.quality.conceptCoverage }.average(),
            baselineSourceRecall = results.mapNotNull { it.baseline.quality.sourceRecall }.average(),
            enhancedSourceRecall = results.mapNotNull { it.enhanced.quality.sourceRecall }.average(),
            baselineCitationRate = results.count { it.baseline.quality.hasCitations }.toDouble() / results.size,
            enhancedCitationRate = results.count { it.enhanced.quality.hasCitations }.toDouble() / results.size,
            enhancedCandidateRetention = results.map { evaluation ->
                val candidates = evaluation.enhanced.result.candidates.size
                if (candidates == 0) 0.0 else evaluation.enhanced.result.sources.size.toDouble() / candidates
            }.average(),
        )
    }
}
