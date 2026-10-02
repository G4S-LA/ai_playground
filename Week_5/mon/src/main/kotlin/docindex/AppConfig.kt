package docindex

import java.nio.file.Path
import kotlin.io.path.Path

data class AppConfig(
    val host: String,
    val port: Int,
    val documentsDir: Path,
    val databasePath: Path,
    val embeddingProvider: String,
    val ollamaUrl: String,
    val ollamaModel: String,
) {
    companion object {
        fun fromEnvironment(demo: Boolean = false): AppConfig {
            val env = System.getenv()
            return AppConfig(
                host = env["WEB_HOST"] ?: "127.0.0.1",
                port = env["WEB_PORT"]?.toIntOrNull() ?: 8080,
                documentsDir = Path(env["DOCUMENTS_DIR"] ?: "documents").toAbsolutePath().normalize(),
                databasePath = Path(env["INDEX_DB"] ?: "data/document-index.db").toAbsolutePath().normalize(),
                embeddingProvider = if (demo) "demo" else env["EMBEDDING_PROVIDER"] ?: "ollama",
                ollamaUrl = (env["OLLAMA_URL"] ?: "http://127.0.0.1:11434").trimEnd('/'),
                ollamaModel = env["OLLAMA_EMBEDDING_MODEL"] ?: "nomic-embed-text",
            )
        }
    }
}
