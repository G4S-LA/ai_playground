package localrag

import java.net.URI
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

data class LocalRagConfig(
    val host: String,
    val port: Int,
    val documentsDir: Path,
    val databasePath: Path,
    val ollamaUrl: String,
    val embeddingModel: String,
    val chatModel: String,
    val temperature: Double,
    val timeoutSeconds: Long,
) {
    companion object {
        fun load(
            environment: Map<String, String> = System.getenv(),
            workingDirectory: Path = Path("").toAbsolutePath().normalize(),
        ): LocalRagConfig {
            val dotenv = readDotenv(workingDirectory.resolve(".env"))
            fun setting(name: String): String? = environment[name].normalized() ?: dotenv[name].normalized()

            val port = setting("WEB_PORT")?.toIntOrNull() ?: 8080
            require(port in 1..65_535) { "WEB_PORT должен быть в диапазоне 1..65535." }
            val url = (setting("OLLAMA_URL") ?: "http://127.0.0.1:11434").trimEnd('/')
            val uri = runCatching { URI.create(url) }.getOrNull()
            require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
                "OLLAMA_URL должен быть корректным HTTP(S)-адресом."
            }
            val temperature = setting("OLLAMA_TEMPERATURE")?.toDoubleOrNull() ?: 0.2
            require(temperature in 0.0..2.0) { "OLLAMA_TEMPERATURE должна быть в диапазоне 0..2." }
            val timeout = setting("OLLAMA_TIMEOUT_SECONDS")?.toLongOrNull() ?: 180L
            require(timeout > 0) { "OLLAMA_TIMEOUT_SECONDS должна быть больше нуля." }

            return LocalRagConfig(
                host = setting("WEB_HOST") ?: "127.0.0.1",
                port = port,
                documentsDir = workingDirectory.resolve(
                    setting("DOCUMENTS_DIR") ?: "../../Week_5/mon/documents",
                ).normalize(),
                databasePath = workingDirectory.resolve(
                    setting("INDEX_DB") ?: "../../Week_5/mon/data/document-index.db",
                ).normalize(),
                ollamaUrl = url,
                embeddingModel = setting("OLLAMA_EMBEDDING_MODEL") ?: "bge-m3",
                chatModel = setting("OLLAMA_CHAT_MODEL") ?: "qwen2.5:3b",
                temperature = temperature,
                timeoutSeconds = timeout,
            )
        }

        private fun readDotenv(path: Path): Map<String, String> {
            if (!path.isRegularFile()) return emptyMap()
            return buildMap {
                path.readLines(Charsets.UTF_8).forEachIndexed { index, source ->
                    val line = source.removePrefix("\uFEFF").trim()
                    if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
                    val assignment = line.removePrefix("export ").trim()
                    val separator = assignment.indexOf('=')
                    require(separator > 0) { "Некорректная строка ${index + 1} в .env." }
                    val key = assignment.take(separator).trim()
                    require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                        "Некорректное имя переменной '$key' в строке ${index + 1} файла .env."
                    }
                    put(key, assignment.substring(separator + 1).trim().unquote())
                }
            }
        }

        private fun String.unquote(): String =
            if (length >= 2 && first() == last() && first() in setOf('\'', '"')) substring(1, lastIndex) else this

        private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)
    }
}
