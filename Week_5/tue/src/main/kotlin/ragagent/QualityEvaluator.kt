package ragagent

import java.util.Locale

class QualityEvaluator {
    fun evaluate(answer: AgentAnswer, control: ControlQuestion): QualityAssessment {
        val normalizedAnswer = answer.answer.lowercase(Locale.ROOT).replace('ё', 'е')
        val matchedTerms = control.requiredTerms.filter { term -> normalize(term) in normalizedAnswer }
        val actualSources = answer.sources.map { normalize(it.source) }
        val matchedSources = control.expectedSources.filter { expected ->
            actualSources.any { actual -> actual.endsWith(normalize(expected)) }
        }
        return QualityAssessment(
            conceptCoverage = ratio(matchedTerms.size, control.requiredTerms.size),
            matchedTerms = matchedTerms,
            missingTerms = control.requiredTerms - matchedTerms.toSet(),
            sourceRecall = if (answer.mode == AnswerMode.WITH_RAG.wireName) {
                ratio(matchedSources.size, control.expectedSources.size)
            } else {
                null
            },
            matchedSources = matchedSources,
            hasCitations = CITATION.containsMatchIn(answer.answer),
        )
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace('ё', 'е')
    private fun ratio(found: Int, total: Int): Double = if (total == 0) 1.0 else found.toDouble() / total

    private companion object {
        val CITATION = Regex("\\[S\\d+\\]")
    }
}
