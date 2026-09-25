package scheduledmcp

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
    val dataFile: Path,
    val defaultTimeZone: String,
    val newsApiBaseUrl: String,
    val newsCandidateLimit: Int,
    val reportWebhookUrl: String?,
    val webChatDataFile: Path,
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
                "Set LLM_API_KEY or DASHSCOPE_API_KEY, or start with --demo."
            }
            val temperature = setting("LLM_TEMPERATURE")?.toDoubleOrNull() ?: 0.2
            require(temperature in 0.0..2.0) { "LLM_TEMPERATURE must be between 0 and 2." }
            val timeout = setting("LLM_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120L
            require(timeout > 0) { "LLM_TIMEOUT_SECONDS must be positive." }
            val candidates = setting("NEWS_CANDIDATE_LIMIT")?.toIntOrNull() ?: 100
            require(candidates in 10..500) { "NEWS_CANDIDATE_LIMIT must be between 10 and 500." }
            val webPort = setting("WEB_PORT")?.toIntOrNull() ?: 8080
            require(webPort in 1..65_535) { "WEB_PORT must be between 1 and 65535." }

            return AppConfig(
                apiKey = apiKey,
                apiUrl = setting("LLM_API_URL") ?: "https://api.openai.com/v1/chat/completions",
                model = if (demo) "demo-news-agent" else setting("LLM_MODEL") ?: "gpt-4o-mini",
                temperature = temperature,
                timeoutSeconds = timeout,
                dataFile = Path(setting("REPORT_DATA_FILE") ?: "data/news-reports.json")
                    .toAbsolutePath()
                    .normalize(),
                defaultTimeZone = setting("REPORT_TIME_ZONE") ?: "Europe/Moscow",
                newsApiBaseUrl = setting("NEWS_API_BASE_URL")
                    ?: "https://hacker-news.firebaseio.com/v0",
                newsCandidateLimit = candidates,
                reportWebhookUrl = setting("REPORT_WEBHOOK_URL"),
                webChatDataFile = Path(setting("WEB_CHAT_DATA_FILE") ?: "data/web-chats.json")
                    .toAbsolutePath()
                    .normalize(),
                webHost = setting("WEB_HOST") ?: "127.0.0.1",
                webPort = webPort,
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

        private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
