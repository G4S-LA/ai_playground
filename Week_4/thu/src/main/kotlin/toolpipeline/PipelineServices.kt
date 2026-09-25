package toolpipeline

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class LocalSearchService {
    private val documents = listOf(
        SearchItem(
            id = "mcp-tools",
            title = "MCP tools and schemas",
            url = "https://modelcontextprotocol.io/docs/concepts/tools",
            content = "MCP tools expose executable operations to a model. " +
                "An input JSON Schema describes every accepted argument and makes a tool call machine-readable.",
        ),
        SearchItem(
            id = "tool-pipelines",
            title = "Building reliable tool pipelines",
            url = "https://example.test/tool-pipelines",
            content = "A tool pipeline feeds structured output from one stage into the next stage. " +
                "Explicit contracts and order validation prevent accidental data loss between calls.",
        ),
        SearchItem(
            id = "pipeline-tests",
            title = "Testing multi-tool agents",
            url = "https://example.test/pipeline-tests",
            content = "Integration tests should verify the tool order and the exact payload passed between stages. " +
                "Temporary directories keep save-to-file tests isolated.",
        ),
        SearchItem(
            id = "stdio-transport",
            title = "MCP over stdio",
            url = "https://modelcontextprotocol.io/docs/concepts/transports",
            content = "The stdio transport exchanges JSON-RPC messages through a child process. " +
                "Application logs must not be written to the protocol stdout stream.",
        ),
    )

    fun search(query: String, limit: Int): SearchResult {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty()) { "query must not be blank" }
        require(limit in 1..10) { "limit must be between 1 and 10" }
        val terms = normalizedQuery.lowercase().tokens()
        val ranked = documents.map { document ->
            val title = document.title.lowercase()
            val text = "$title ${document.content.lowercase()}"
            val score = terms.sumOf { term ->
                (if (term in title) 3 else 0) + text.windowed(term.length, 1).count { it == term }
            }
            document to score
        }.filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<SearchItem, Int>> { it.second }.thenBy { it.first.id })
            .take(limit)
            .map(Pair<SearchItem, Int>::first)
        return SearchResult(normalizedQuery, ranked)
    }

    private fun String.tokens(): Set<String> = Regex("[\\p{L}\\p{N}]+")
        .findAll(this)
        .map { it.value }
        .filter { it.length >= 2 }
        .toSet()
}

internal class ExtractiveSummarizer {
    fun summarize(searchResult: SearchResult, maxSentences: Int): SummaryResult {
        require(searchResult.query.isNotBlank()) { "searchResult.query must not be blank" }
        require(searchResult.items.isNotEmpty()) { "searchResult.items must not be empty" }
        require(maxSentences in 1..10) { "maxSentences must be between 1 and 10" }
        val selected = searchResult.items.take(maxSentences)
        val summary = selected.joinToString("\n") { item ->
            val firstSentence = item.content.substringBefore('.').trim().let { sentence ->
                if (sentence.endsWith('.')) sentence else "$sentence."
            }
            "- ${item.title}: $firstSentence"
        }
        return SummaryResult(
            sourceQuery = searchResult.query,
            sourceCount = searchResult.items.size,
            summary = summary,
            sourceIds = searchResult.items.map(SearchItem::id),
        )
    }
}

internal class SafeFileStore(private val outputDirectory: Path) {
    fun save(summary: SummaryResult, fileName: String): SavedFileResult {
        require(fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.(md|txt)"))) {
            "fileName must be a simple .md or .txt name"
        }
        require(summary.sourceQuery.isNotBlank()) { "summary.sourceQuery must not be blank" }
        require(summary.summary.isNotBlank()) { "summary.summary must not be blank" }
        val root = outputDirectory.toAbsolutePath().normalize()
        val target = root.resolve(fileName).normalize()
        require(target.parent == root) { "fileName must stay inside the output directory" }
        Files.createDirectories(root)
        val text = buildString {
            appendLine("# Pipeline summary")
            appendLine()
            appendLine("Query: ${summary.sourceQuery}")
            appendLine("Sources: ${summary.sourceCount}")
            appendLine()
            appendLine(summary.summary)
            appendLine()
            appendLine("Source IDs: ${summary.sourceIds.joinToString()}")
        }
        Files.writeString(
            target,
            text,
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return SavedFileResult(target.toString(), Files.size(target), summary.sourceCount)
    }
}
