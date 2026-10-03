package ragagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EvaluationRegistry(
    private val agent: RagAgentService,
    private val controls: List<ControlQuestion>,
    private val evaluator: QualityEvaluator = QualityEvaluator(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val runs = ConcurrentHashMap<String, MutableEvaluationRun>()

    fun start(topK: Int = 5): EvaluationRunSnapshot {
        require(topK in 1..12) { "topK должен быть от 1 до 12." }
        val run = MutableEvaluationRun(UUID.randomUUID().toString(), controls.size)
        runs[run.id] = run
        scope.launch {
            try {
                controls.forEach { control ->
                    run.beginQuestion(control.question)
                    val comparison = agent.compare(control.question, topK)
                    run.add(
                        QuestionEvaluation(
                            control = control,
                            withoutRag = EvaluatedAnswer(
                                comparison.withoutRag,
                                evaluator.evaluate(comparison.withoutRag, control),
                            ),
                            withRag = EvaluatedAnswer(
                                comparison.withRag,
                                evaluator.evaluate(comparison.withRag, control),
                            ),
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

    fun snapshot(id: String): EvaluationRunSnapshot? = runs[id]?.snapshot()
}

private class MutableEvaluationRun(val id: String, private val total: Int) {
    private val results = mutableListOf<QuestionEvaluation>()
    private var status = "running"
    private var currentQuestion: String? = null
    private var error: String? = null

    @Synchronized
    fun beginQuestion(question: String) {
        currentQuestion = question
    }

    @Synchronized
    fun add(result: QuestionEvaluation) {
        results += result
    }

    @Synchronized
    fun complete() {
        status = "completed"
        currentQuestion = null
    }

    @Synchronized
    fun fail(message: String) {
        status = "failed"
        error = message
        currentQuestion = null
    }

    @Synchronized
    fun snapshot(): EvaluationRunSnapshot = EvaluationRunSnapshot(
        id = id,
        status = status,
        completed = results.size,
        total = total,
        currentQuestion = currentQuestion,
        results = results.toList(),
        summary = if (status == "completed") summary() else null,
        error = error,
    )

    private fun summary(): EvaluationSummary {
        if (results.isEmpty()) return EvaluationSummary(0.0, 0.0, 0.0, 0.0)
        return EvaluationSummary(
            withoutRagConceptCoverage = results.map { it.withoutRag.quality.conceptCoverage }.average(),
            withRagConceptCoverage = results.map { it.withRag.quality.conceptCoverage }.average(),
            ragSourceRecall = results.mapNotNull { it.withRag.quality.sourceRecall }.average(),
            ragCitationRate = results.count { it.withRag.quality.hasCitations }.toDouble() / results.size,
        )
    }
}
