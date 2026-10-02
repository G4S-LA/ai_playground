package docindex

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

class DocumentLoader(private val root: Path) {
    fun loadAll(): List<SourceDocument> {
        root.createDirectories()
        val paths = mutableListOf<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && dir.fileName.toString() in EXCLUDED_DIRECTORIES) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && file.extension.lowercase() in SUPPORTED_EXTENSIONS) paths.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return paths.sortedBy { it.toString() }.flatMap(::load)
    }

    private fun load(path: Path): List<SourceDocument> {
        val source = root.relativize(path).toString()
        return when (path.extension.lowercase()) {
            "pdf" -> loadPdf(path, source)
            "md", "markdown" -> listOf(loadText(path, source, ContentType.MARKDOWN))
            "kt", "kts", "java" -> listOf(loadText(path, source, ContentType.CODE))
            else -> listOf(loadText(path, source, ContentType.TEXT))
        }
    }

    private fun loadText(path: Path, source: String, type: ContentType): SourceDocument {
        val text = path.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val title = if (type == ContentType.MARKDOWN) {
            Regex("(?m)^#\\s+(.+?)\\s*$").find(text)?.groupValues?.get(1)?.trim()
                ?: path.nameWithoutExtension
        } else {
            path.nameWithoutExtension
        }
        return SourceDocument(source, title, type, text)
    }

    private fun loadPdf(path: Path, source: String): List<SourceDocument> = Loader.loadPDF(path.toFile()).use { pdf ->
        (1..pdf.numberOfPages).mapNotNull { page ->
            val stripper = PDFTextStripper().apply {
                startPage = page
                endPage = page
                sortByPosition = true
            }
            val text = stripper.getText(pdf).replace("\r\n", "\n").trim()
            text.takeIf { it.isNotBlank() }?.let {
                SourceDocument(
                    source = source,
                    title = path.nameWithoutExtension,
                    contentType = ContentType.PDF,
                    text = it,
                    baseSection = "Страница $page",
                    page = page,
                )
            }
        }
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("md", "markdown", "txt", "kt", "kts", "java", "pdf")

        private val EXCLUDED_DIRECTORIES = setOf(
            ".gradle", "bin", "out", "output", "build", "submodules", "gen", ".idea",
            "captures", ".settings",
        )
    }
}

data class UploadedFile(val name: String = "", val base64: String = "")
data class UploadRequest(val files: List<UploadedFile> = emptyList())
data class UploadResponse(val files: List<String>, val totalBytes: Long)

class UploadedDocumentStore(private val root: Path) {
    fun save(request: UploadRequest): UploadResponse {
        require(request.files.isNotEmpty()) { "Выберите хотя бы один файл." }
        require(request.files.size <= 30) { "За один раз можно загрузить не более 30 файлов." }
        root.createDirectories()
        var totalBytes = 0L
        val saved = request.files.map { uploaded ->
            val name = safeName(uploaded.name)
            val extension = name.substringAfterLast('.', "").lowercase()
            require(extension in DocumentLoader.SUPPORTED_EXTENSIONS) {
                "Формат .$extension не поддерживается: $name"
            }
            val bytes = try {
                Base64.getDecoder().decode(uploaded.base64)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Некорректные данные файла $name.")
            }
            require(bytes.size <= MAX_FILE_BYTES) { "Файл $name превышает лимит 15 МБ." }
            totalBytes += bytes.size
            require(totalBytes <= MAX_TOTAL_BYTES) { "Общий размер файлов превышает 50 МБ." }
            Files.write(root.resolve(name), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            name
        }
        return UploadResponse(saved, totalBytes)
    }

    private fun safeName(raw: String): String {
        val leaf = Path.of(raw.ifBlank { "document.txt" }).fileName.toString()
        val safe = leaf.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(160)
        require(safe.isNotBlank() && safe !in setOf(".", "..")) { "Некорректное имя файла." }
        return safe
    }

    private companion object {
        const val MAX_FILE_BYTES = 15 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    }
}
