package docindex

import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

interface DocumentChunker {
    val strategy: ChunkStrategy
    fun chunk(document: SourceDocument): List<ChunkDraft>
}

class FixedSizeChunker(
    private val chunkSize: Int = 1_500,
    private val overlap: Int = 250,
) : DocumentChunker {
    override val strategy: ChunkStrategy = ChunkStrategy.FIXED

    init {
        require(chunkSize in 200..20_000) { "Размер чанка должен быть от 200 до 20000 символов." }
        require(overlap in 0 until chunkSize) { "Overlap должен быть меньше размера чанка." }
    }

    override fun chunk(document: SourceDocument): List<ChunkDraft> {
        if (document.text.isBlank()) return emptyList()
        val result = mutableListOf<ChunkDraft>()
        var start = 0
        var index = 0
        while (start < document.text.length) {
            val end = min(start + chunkSize, document.text.length)
            addChunk(result, document, strategy, document.baseSection, index++, start, end)
            if (end == document.text.length) break
            start = end - overlap
        }
        return result
    }
}

class StructuredChunker(
    private val maxChunkSize: Int = 2_000,
    private val fallbackOverlap: Int = 150,
) : DocumentChunker {
    override val strategy: ChunkStrategy = ChunkStrategy.STRUCTURED

    init {
        require(maxChunkSize in 300..20_000) { "Максимальный размер должен быть от 300 до 20000 символов." }
        require(fallbackOverlap in 0 until maxChunkSize) { "Overlap должен быть меньше размера чанка." }
    }

    override fun chunk(document: SourceDocument): List<ChunkDraft> {
        if (document.text.isBlank()) return emptyList()
        val sections = when (document.contentType) {
            ContentType.MARKDOWN -> markdownSections(document)
            ContentType.CODE -> kotlinSections(document)
            ContentType.PDF, ContentType.TEXT -> paragraphSections(document)
        }
        val result = mutableListOf<ChunkDraft>()
        sections.forEach { section ->
            splitSection(section).forEach { part ->
                addChunk(
                    target = result,
                    document = document,
                    strategy = strategy,
                    section = section.name,
                    index = result.size,
                    start = part.first,
                    end = part.last + 1,
                )
            }
        }
        return result
    }

    private fun markdownSections(document: SourceDocument): List<TextSection> {
        val headings = MARKDOWN_HEADING.findAll(document.text).toList()
        if (headings.isEmpty()) return paragraphSections(document)

        val sections = mutableListOf<TextSection>()
        val headingPath = mutableListOf<String>()
        headings.forEachIndexed { index, match ->
            val level = match.groupValues[1].length
            val heading = match.groupValues[2].trim().removeSuffix("#").trim()
            while (headingPath.size >= level) headingPath.removeLast()
            while (headingPath.size < level - 1) headingPath += "Раздел"
            headingPath += heading
            val start = match.range.first
            val end = headings.getOrNull(index + 1)?.range?.first ?: document.text.length
            if (start < end) sections += TextSection(headingPath.joinToString(" > "), start, end)
        }
        val preambleEnd = headings.first().range.first
        if (preambleEnd > 0 && document.text.substring(0, preambleEnd).isNotBlank()) {
            sections.add(0, TextSection(document.baseSection, 0, preambleEnd))
        }
        return sections
    }

    private fun kotlinSections(document: SourceDocument): List<TextSection> {
        val declarations = KOTLIN_DECLARATION.findAll(document.text).toList()
        if (declarations.isEmpty()) return paragraphSections(document)
        val sections = mutableListOf<TextSection>()
        val preambleEnd = declarations.first().range.first
        if (preambleEnd > 0 && document.text.substring(0, preambleEnd).isNotBlank()) {
            sections += TextSection("Файл и импорты", 0, preambleEnd)
        }
        declarations.forEachIndexed { index, match ->
            val kind = match.groupValues[1].replace(Regex("\\s+"), " ")
            val name = match.groupValues[2]
            val end = declarations.getOrNull(index + 1)?.range?.first ?: document.text.length
            sections += TextSection("$kind $name", match.range.first, end)
        }
        return sections
    }

    private fun paragraphSections(document: SourceDocument): List<TextSection> {
        val paragraphs = PARAGRAPH.findAll(document.text).toList()
        if (paragraphs.isEmpty()) {
            return listOf(TextSection(document.baseSection, 0, document.text.length))
        }
        val sections = mutableListOf<TextSection>()
        var groupStart = paragraphs.first().range.first
        var groupEnd = paragraphs.first().range.last + 1
        paragraphs.drop(1).forEach { paragraph ->
            val nextEnd = paragraph.range.last + 1
            if (nextEnd - groupStart > maxChunkSize && groupEnd > groupStart) {
                sections += TextSection(document.baseSection, groupStart, groupEnd)
                groupStart = paragraph.range.first
            }
            groupEnd = nextEnd
        }
        sections += TextSection(document.baseSection, groupStart, groupEnd)
        return sections
    }

    private fun splitSection(section: TextSection): List<IntRange> {
        if (section.end - section.start <= maxChunkSize) {
            return listOf(section.start until section.end)
        }
        val result = mutableListOf<IntRange>()
        var start = section.start
        while (start < section.end) {
            val end = min(start + maxChunkSize, section.end)
            result += start until end
            if (end == section.end) break
            start = max(start + 1, end - fallbackOverlap)
        }
        return result
    }

    private data class TextSection(val name: String, val start: Int, val end: Int)

    private companion object {
        val MARKDOWN_HEADING = Regex("(?m)^(#{1,6})[ \\t]+(.+?)\\s*$")
        val PARAGRAPH = Regex("(?s)\\S.*?(?=\\n\\s*\\n|\\z)")
        val KOTLIN_DECLARATION = Regex(
            pattern = "(?m)^(?:(?:public|private|internal|protected|open|abstract|sealed|data|enum|annotation|value|inline|tailrec|operator|infix|external|suspend|actual|expect)\\s+)*(class|object|interface|fun|typealias|enum\\s+class|annotation\\s+class|data\\s+class|sealed\\s+class)\\s+([A-Za-z_][A-Za-z0-9_]*)",
        )
    }
}

private fun addChunk(
    target: MutableList<ChunkDraft>,
    document: SourceDocument,
    strategy: ChunkStrategy,
    section: String,
    index: Int,
    start: Int,
    end: Int,
) {
    val text = document.text.substring(start, end).trim()
    if (text.isBlank()) return
    val idSeed = "${strategy.wireName}|${document.source}|${document.page}|$section|$index|$text"
    target += ChunkDraft(
        chunkId = "${strategy.wireName}:${sha256(idSeed).take(20)}",
        strategy = strategy,
        source = document.source,
        title = document.title,
        section = section,
        contentType = document.contentType,
        chunkIndex = index,
        startPosition = start,
        endPosition = end,
        tokenCount = estimateTokens(text),
        page = document.page,
        text = text,
    )
}

private fun estimateTokens(text: String): Int = max(1, TOKENISH.findAll(text).count())

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }

private val TOKENISH = Regex("[\\p{L}\\p{N}_]+|[^\\s\\p{L}\\p{N}_]")
