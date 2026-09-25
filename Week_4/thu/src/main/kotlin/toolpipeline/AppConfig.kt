package toolpipeline

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

internal data class AppConfig(
    val apiKey: String,
    val apiUrl: String,
    val model: String,
    val temperature: Double,
    val timeoutSeconds: Long,
    val outputDirectory: Path,
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
                "Set LLM_API_KEY or DASHSCOPE_API_KEY, or start with demo."
            }
            val temperature = setting("LLM_TEMPERATURE")?.toDoubleOrNull() ?: 0.1
            require(temperature in 0.0..2.0) { "LLM_TEMPERATURE must be between 0 and 2." }
            val timeout = setting("LLM_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120L
            require(timeout > 0) { "LLM_TIMEOUT_SECONDS must be positive." }

            return AppConfig(
                apiKey = apiKey,
                apiUrl = setting("LLM_API_URL") ?: "https://api.openai.com/v1/chat/completions",
                model = if (demo) "demo-pipeline-model" else setting("LLM_MODEL") ?: "gpt-4o-mini",
                temperature = temperature,
                timeoutSeconds = timeout,
                outputDirectory = Path(setting("PIPELINE_OUTPUT_DIR") ?: "data").toAbsolutePath().normalize(),
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
                    require(separator > 0) { "Invalid line ${index + 1} in .env." }
                    val key = assignment.take(separator).trim()
                    require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                        "Invalid variable name '$key' on line ${index + 1}."
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
