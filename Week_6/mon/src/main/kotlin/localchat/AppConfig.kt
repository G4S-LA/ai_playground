package localchat

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

data class AppConfig(
    val webHost: String,
    val webPort: Int,
    val databasePath: Path,
    val ollamaUrl: String,
    val ollamaModel: String,
    val systemPrompt: String,
    val temperature: Double,
    val timeoutSeconds: Long,
) {
    companion object {
        fun load(
            processEnvironment: Map<String, String> = System.getenv(),
            workingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
        ): AppConfig {
            val dotenv = readDotenv(workingDirectory.resolve(".env"))
            val settings = dotenv + processEnvironment

            val host = settings["WEB_HOST"]?.trim().orEmpty().ifBlank { "127.0.0.1" }
            val port = settings["WEB_PORT"]?.trim()?.toIntOrNull() ?: 8080
            require(port in 1..65_535) { "WEB_PORT должен быть в диапазоне 1..65535." }

            val rawUrl = settings["OLLAMA_URL"]?.trim().orEmpty().ifBlank {
                "http://127.0.0.1:11434"
            }.trimEnd('/')
            val uri = runCatching { URI.create(rawUrl) }.getOrNull()
            require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
                "OLLAMA_URL должен быть корректным HTTP(S)-адресом."
            }

            val model = settings["OLLAMA_MODEL"]?.trim().orEmpty().ifBlank { "qwen2.5:3b" }
            val prompt = settings["OLLAMA_SYSTEM_PROMPT"]?.trim().orEmpty().ifBlank {
                "Ты — полезный локальный ассистент. Отвечай кратко и по делу."
            }
            val temperature = settings["OLLAMA_TEMPERATURE"]?.trim()?.toDoubleOrNull() ?: 0.7
            require(temperature in 0.0..2.0) { "OLLAMA_TEMPERATURE должна быть в диапазоне 0..2." }
            val timeout = settings["OLLAMA_TIMEOUT_SECONDS"]?.trim()?.toLongOrNull() ?: 120L
            require(timeout > 0) { "OLLAMA_TIMEOUT_SECONDS должна быть больше нуля." }

            val database = settings["CHAT_DATABASE"]?.trim().orEmpty().ifBlank {
                "data/local-chat.db"
            }
            val databasePath = workingDirectory.resolve(database).normalize()

            return AppConfig(host, port, databasePath, rawUrl, model, prompt, temperature, timeout)
        }

        private fun readDotenv(path: Path): Map<String, String> {
            if (!path.exists()) return emptyMap()
            return Files.readAllLines(path).mapNotNull { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith('#') || '=' !in line) return@mapNotNull null
                val (name, rawValue) = line.split('=', limit = 2)
                val key = name.trim()
                if (key.isEmpty()) return@mapNotNull null
                key to rawValue.trim().removeSurrounding("\"").removeSurrounding("'")
            }.toMap()
        }
    }
}
