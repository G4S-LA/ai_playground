package groundedrag

import com.google.gson.JsonParser
import ragagent.ChatLanguageModel
import java.util.Locale

class SemanticSupportJudge(
    private val model: ChatLanguageModel,
) {
    suspend fun assess(result: GroundedAnswer): SupportAssessment {
        if (result.needsClarification || result.quotes.isEmpty()) {
            return SupportAssessment(false, "Ответ не содержит доказательств из базы знаний.", "deterministic")
        }
        val prompt = buildString {
            appendLine("Ответ:")
            appendLine(result.answer)
            appendLine()
            appendLine("Цитаты:")
            result.quotes.forEach { appendLine("[${it.citation}] ${it.quote}") }
            appendLine()
            appendLine("Подтверждают ли цитаты смысл ответа? Верни только JSON:")
            appendLine("""{"supported":true,"reason":"Краткое объяснение"}""")
        }
        val raw = runCatching { model.generate(SYSTEM_PROMPT, prompt) }.getOrNull()
        val parsed = raw?.let(::parse)
        return parsed ?: heuristic(result)
    }

    private fun parse(raw: String): SupportAssessment? = runCatching {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = clean.substring(clean.indexOf('{'), clean.lastIndexOf('}') + 1)
        val root = JsonParser.parseString(json).asJsonObject
        val supported = root.get("supported")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: return null
        val reason = root.get("reason")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        SupportAssessment(supported, reason.ifBlank { "Оценка без объяснения." }, "llm-judge")
    }.getOrNull()

    private fun heuristic(result: GroundedAnswer): SupportAssessment {
        val answerTerms = terms(result.answer)
        val quoteTerms = terms(result.quotes.joinToString(" ") { it.quote })
        val overlap = if (answerTerms.isEmpty()) 0.0 else answerTerms.count(quoteTerms::contains).toDouble() / answerTerms.size
        val supported = overlap >= 0.25
        return SupportAssessment(
            supported = supported,
            reason = "Резервная lexical-проверка: совпадение значимых слов ${"%.0f".format(overlap * 100)}%.",
            method = "lexical-fallback",
        )
    }

    private fun terms(text: String): Set<String> = TOKEN.findAll(text.lowercase(Locale.ROOT).replace('ё', 'е'))
        .map(MatchResult::value)
        .filter { it.length > 2 && it !in STOP_WORDS }
        .toSet()

    private companion object {
        val SYSTEM_PROMPT = """
            Ты проверяешь groundedness ответа. Оцени только то, подтверждается ли смысл ответа предоставленными цитатами.
            Не используй внешние знания. Наличие одинаковых слов само по себе недостаточно, если утверждения противоречат цитатам.
            Верни только JSON с boolean supported и коротким reason на русском языке.
        """.trimIndent()
        val TOKEN = Regex("[\\p{L}\\p{N}_-]+")
        val STOP_WORDS = setOf("как", "что", "это", "для", "или", "при", "чем", "также", "быть", "есть", "ответ")
    }
}
