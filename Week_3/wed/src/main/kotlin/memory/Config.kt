package memory

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

data class AppConfig(
    val apiKey: String,
    val apiUrl: String,
    val model: String,
    val systemPrompt: String,
    val temperature: Double,
    val timeoutSeconds: Long,
    val dataDirectory: Path,
    val webHost: String,
    val webPort: Int,
) {
    companion object {
        fun fromEnvironment(
            demo: Boolean = false,
            environment: Map<String, String> = System.getenv(),
            dotenvPath: Path = Path(".env"),
        ): AppConfig {
            val dotenv = readDotEnv(dotenvPath)
            fun setting(name: String): String? = environment[name].normalized() ?: dotenv[name].normalized()

            val apiKey = setting("LLM_API_KEY") ?: setting("DASHSCOPE_API_KEY").orEmpty()
            require(demo || apiKey.isNotBlank()) {
                "Не задана переменная LLM_API_KEY или DASHSCOPE_API_KEY " +
                    "в окружении или файле .env. " +
                    "Для локальной демонстрации добавьте --demo."
            }
            val temperature = setting("LLM_TEMPERATURE")?.toDoubleOrNull() ?: 0.7
            require(temperature in 0.0..2.0) { "LLM_TEMPERATURE должна быть от 0 до 2." }
            val timeout = setting("LLM_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120L
            require(timeout > 0) { "LLM_TIMEOUT_SECONDS должна быть положительным целым числом." }
            val port = setting("WEB_PORT")?.toIntOrNull() ?: 8080
            require(port in 1..65535) { "WEB_PORT должен быть от 1 до 65535." }

            return AppConfig(
                apiKey = apiKey,
                apiUrl = setting("LLM_API_URL") ?: "https://api.openai.com/v1/chat/completions",
                model = if (demo) "demo-memory-model" else setting("LLM_MODEL") ?: "gpt-4o-mini",
                systemPrompt = setting("LLM_SYSTEM_PROMPT")
                    ?: "Ты — полезный ассистент. Используй переданные слои памяти, " +
                    "но не выдумывай отсутствующие факты.",
                temperature = temperature,
                timeoutSeconds = timeout,
                dataDirectory = Path(setting("MEMORY_DATA_DIR") ?: "data").toAbsolutePath().normalize(),
                webHost = setting("WEB_HOST") ?: "127.0.0.1",
                webPort = port,
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

        private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
