package enhancedrag

import ragagent.ChatLanguageModel
import kotlin.time.TimeSource

fun interface QueryRewriter {
    suspend fun rewrite(question: String): RewriteResult
}

data class RewriteResult(val query: String, val elapsedMs: Long)

class LlmQueryRewriter(private val model: ChatLanguageModel) : QueryRewriter {
    override suspend fun rewrite(question: String): RewriteResult {
        val started = TimeSource.Monotonic.markNow()
        val raw = model.generate(SYSTEM_PROMPT, question)
        val query = raw
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .trim()
            .removeSurrounding("\"")
            .trim()
            .take(1_000)
            .ifBlank { question }
        return RewriteResult(query, started.elapsedNow().inWholeMilliseconds)
    }

    private companion object {
        val SYSTEM_PROMPT = """
            Преобразуй вопрос пользователя в один короткий поисковый запрос для семантического поиска по русскоязычной базе знаний.
            Сохрани имена, термины, аббревиатуры и смысловые ограничения. Убери разговорные вводные и неоднозначные ссылки,
            если их можно конкретизировать из самого вопроса. Не отвечай на вопрос и не добавляй объяснений.
            Верни только поисковый запрос одной строкой.
        """.trimIndent()
    }
}

class HeuristicQueryRewriter : QueryRewriter {
    override suspend fun rewrite(question: String): RewriteResult {
        val cleaned = question
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .distinct()
            .joinToString(" ")
            .ifBlank { question.trim() }
        return RewriteResult(cleaned, 0)
    }

    private companion object {
        val STOP_WORDS = setOf("как", "что", "это", "чем", "какие", "какую", "какой", "зачем", "почему", "когда")
    }
}
