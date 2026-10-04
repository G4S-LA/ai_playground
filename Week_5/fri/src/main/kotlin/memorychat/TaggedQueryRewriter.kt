package memorychat

import enhancedrag.QueryRewriter
import enhancedrag.RewriteResult

class TaggedQueryRewriter : QueryRewriter {
    override suspend fun rewrite(question: String): RewriteResult {
        val query = SEARCH_QUERY.find(question)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { question }
        return RewriteResult(query.take(1_000), 0)
    }

    private companion object {
        val SEARCH_QUERY = Regex("<search_query>(.*?)</search_query>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    }
}
