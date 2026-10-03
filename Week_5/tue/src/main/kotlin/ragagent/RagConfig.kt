package ragagent

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

data class RagConfig(
    val host: String,
    val port: Int,
    val documentsDir: Path,
    val databasePath: Path,
    val ollamaUrl: String,
    val embeddingModel: String,
    val llmProvider: String,
    val apiKey: String?,
    val apiUrl: String,
    val apiModel: String,
    val ollamaChatModel: String,
    val demo: Boolean,
) {
    companion object {
        fun fromEnvironment(
            demo: Boolean = false,
            environment: Map<String, String> = System.getenv(),
            dotenvPath: Path = Path(".env"),
        ): RagConfig {
            val dotenv = readDotEnv(dotenvPath)
            fun setting(name: String): String? = environment[name].normalized() ?: dotenv[name].normalized()
            val port = setting("WEB_PORT")?.toIntOrNull() ?: 8080
            require(port in 1..65535) { "WEB_PORT должен быть от 1 до 65535." }

            return RagConfig(
                host = setting("WEB_HOST") ?: "127.0.0.1",
                port = port,
                documentsDir = Path(setting("DOCUMENTS_DIR") ?: "../mon/documents").toAbsolutePath().normalize(),
                databasePath = Path(setting("INDEX_DB") ?: "../mon/data/document-index.db").toAbsolutePath().normalize(),
                ollamaUrl = (setting("OLLAMA_URL") ?: "http://127.0.0.1:11434").trimEnd('/'),
                embeddingModel = setting("OLLAMA_EMBEDDING_MODEL") ?: "bge-m3",
                llmProvider = (setting("LLM_PROVIDER") ?: "api").lowercase(),
                apiKey = setting("LLM_API_KEY") ?: setting("DASHSCOPE_API_KEY"),
                apiUrl = setting("LLM_API_URL") ?: "https://api.deepseek.com/chat/completions",
                apiModel = setting("LLM_MODEL") ?: "deepseek-chat",
                ollamaChatModel = setting("OLLAMA_CHAT_MODEL") ?: "qwen3.5:4b",
                demo = demo,
            )
        }

        private fun readDotEnv(path: Path): Map<String, String> {
            if (!path.isRegularFile()) return emptyMap()
            return buildMap {
                path.readLines(Charsets.UTF_8).forEachIndexed { index, sourceLine ->
                    val line = sourceLine.removePrefix("\uFEFF").trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                    val assignment = line.removePrefix("export ").trim()
                    val separator = assignment.indexOf('=')
                    require(separator > 0) { "Некорректная строка ${index + 1} в .env." }
                    val key = assignment.take(separator).trim()
                    require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                        "Некорректное имя переменной '$key' в строке ${index + 1} файла .env."
                    }
                    put(key, unquote(assignment.substring(separator + 1).trim()))
                }
            }
        }

        private fun unquote(value: String): String {
            if (value.length < 2) return value
            val quote = value.first()
            return if ((quote == '\'' || quote == '"') && value.last() == quote) {
                value.substring(1, value.lastIndex)
            } else {
                value
            }
        }

        private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)
    }
}
