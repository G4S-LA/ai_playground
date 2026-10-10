package localopt

import java.net.URI
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

data class AppConfig(
    val host: String,
    val port: Int,
    val ollamaUrl: String,
    val model: String,
    val baseline: GenerationProfile,
    val optimized: GenerationProfile,
    val timeoutSeconds: Long,
) {
    companion object {
        fun load(
            environment: Map<String, String> = System.getenv(),
            workingDirectory: Path = Path("").toAbsolutePath().normalize(),
        ): AppConfig {
            val dotenv = readDotenv(workingDirectory.resolve(".env"))
            fun setting(name: String): String? = environment[name].normalized() ?: dotenv[name].normalized()

            val port = setting("WEB_PORT")?.toIntOrNull() ?: 8080
            require(port in 1..65_535) { "WEB_PORT должен быть в диапазоне 1..65535." }
            val ollamaUrl = (setting("OLLAMA_URL") ?: "http://127.0.0.1:11434").trimEnd('/')
            val uri = runCatching { URI.create(ollamaUrl) }.getOrNull()
            require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
                "OLLAMA_URL должен быть корректным HTTP(S)-адресом."
            }

            val baseline = GenerationProfile(
                id = "baseline",
                label = "До оптимизации",
                temperature = setting("BASELINE_TEMPERATURE").doubleOrDefault(0.7, "BASELINE_TEMPERATURE"),
                maxTokens = setting("BASELINE_MAX_TOKENS").positiveIntOrDefault(1024, "BASELINE_MAX_TOKENS"),
                contextWindow = setting("BASELINE_CONTEXT_WINDOW").positiveIntOrDefault(8192, "BASELINE_CONTEXT_WINDOW"),
                systemPrompt = BASELINE_PROMPT,
            )
            val optimized = GenerationProfile(
                id = "optimized",
                label = "После оптимизации",
                temperature = setting("OPTIMIZED_TEMPERATURE").doubleOrDefault(0.1, "OPTIMIZED_TEMPERATURE"),
                maxTokens = setting("OPTIMIZED_MAX_TOKENS").positiveIntOrDefault(384, "OPTIMIZED_MAX_TOKENS"),
                contextWindow = setting("OPTIMIZED_CONTEXT_WINDOW").positiveIntOrDefault(4096, "OPTIMIZED_CONTEXT_WINDOW"),
                systemPrompt = OPTIMIZED_PROMPT,
            )
            val timeout = setting("OLLAMA_TIMEOUT_SECONDS")?.toLongOrNull() ?: 300L
            require(timeout > 0) { "OLLAMA_TIMEOUT_SECONDS должна быть больше нуля." }

            return AppConfig(
                host = setting("WEB_HOST") ?: "127.0.0.1",
                port = port,
                ollamaUrl = ollamaUrl,
                model = setting("OLLAMA_MODEL") ?: "qwen2.5:3b",
                baseline = baseline,
                optimized = optimized,
                timeoutSeconds = timeout,
            )
        }

        private fun String?.doubleOrDefault(default: Double, name: String): Double {
            val value = this?.toDoubleOrNull() ?: default
            require(value in 0.0..2.0) { "$name должна быть в диапазоне 0..2." }
            return value
        }

        private fun String?.positiveIntOrDefault(default: Int, name: String): Int {
            val value = this?.toIntOrNull() ?: default
            require(value > 0) { "$name должна быть больше нуля." }
            return value
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
                        "Некорректное имя '$key' в строке ${index + 1} файла .env."
                    }
                    put(key, assignment.substring(separator + 1).trim().unquote())
                }
            }
        }

        private fun String.unquote(): String =
            if (length >= 2 && first() == last() && first() in setOf('\'', '"')) substring(1, lastIndex) else this

        private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)

        private const val BASELINE_PROMPT = ""

        private val OPTIMIZED_PROMPT = """
            Ты — локальный технический помощник для начинающего разработчика.
            Отвечай на русском языке точно и по существу, строго в следующем формате:

            Краткий ответ:
            Дай прямой ответ в 1–2 предложениях.

            Действия:
            Перечисли от одного до пяти практических шагов нумерованным списком.

            Ограничения:
            Укажи неизвестные данные, допущения и важные компромиссы. Если ограничений нет, напиши «Нет».

            Не повторяй вопрос, не добавляй вступление и не создавай другие разделы.
            Не выдумывай факты. Используй код только когда он действительно помогает.
            Не превышай 220 слов.
        """.trimIndent()
    }
}
