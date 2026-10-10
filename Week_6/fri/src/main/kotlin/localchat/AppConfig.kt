package localchat

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

data class AppConfig(
    val host: String,
    val port: Int,
    val apiKey: String,
    val ollamaUrl: String,
    val model: String?,
    val databasePath: Path,
    val ollamaTimeoutSeconds: Long,
) {
    companion object {
        fun load(
            environment: Map<String, String> = System.getenv(),
            workingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
        ): AppConfig {
            val settings = readDotenv(workingDirectory.resolve(".env")) + environment
            val apiKey = settings["SERVICE_API_KEY"].normalized().orEmpty()
            require(apiKey.length >= 24 && apiKey != "replace-with-a-long-random-secret") {
                "Не задан безопасный SERVICE_API_KEY. Выполните: ./gradlew run --args=init"
            }
            val port = settings["SERVICE_PORT"]?.toIntOrNull() ?: 8080
            require(port in 1..65_535) { "SERVICE_PORT должен быть в диапазоне 1..65535." }
            val ollamaUrl = (settings["OLLAMA_URL"].normalized() ?: "http://127.0.0.1:11434").trimEnd('/')
            val uri = runCatching { URI.create(ollamaUrl) }.getOrNull()
            require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
                "OLLAMA_URL должен быть корректным HTTP(S)-адресом."
            }
            val timeout = settings["OLLAMA_TIMEOUT_SECONDS"]?.toLongOrNull() ?: 300L
            require(timeout > 0) { "OLLAMA_TIMEOUT_SECONDS должна быть больше нуля." }
            val database = Path.of(settings["CHAT_DATABASE"].normalized() ?: "data/local-chat.db")
            return AppConfig(
                host = settings["SERVICE_HOST"].normalized() ?: "0.0.0.0",
                port = port,
                apiKey = apiKey,
                ollamaUrl = ollamaUrl,
                model = settings["OLLAMA_MODEL"].normalized(),
                databasePath = if (database.isAbsolute) database.normalize() else workingDirectory.resolve(database).normalize(),
                ollamaTimeoutSeconds = timeout,
            )
        }

        fun initializeDotenv(path: Path = Path.of("").toAbsolutePath().normalize().resolve(".env")) {
            if (path.exists()) {
                println("$path уже существует; файл не изменён.")
                return
            }
            val randomBytes = ByteArray(32).also(SecureRandom()::nextBytes)
            val apiKey = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
            val content = """
                SERVICE_API_KEY=$apiKey
                SERVICE_HOST=0.0.0.0
                SERVICE_PORT=8080
                OLLAMA_URL=http://127.0.0.1:11434
                OLLAMA_MODEL=
                CHAT_DATABASE=data/local-chat.db
                OLLAMA_TIMEOUT_SECONDS=300
            """.trimIndent() + "\n"
            Files.writeString(
                path,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            runCatching {
                Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            }
            println("Создан $path со случайным API-ключом.")
        }

        private fun readDotenv(path: Path): Map<String, String> {
            if (!path.isRegularFile()) return emptyMap()
            return buildMap {
                path.readLines(StandardCharsets.UTF_8).forEachIndexed { index, source ->
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
    }
}
