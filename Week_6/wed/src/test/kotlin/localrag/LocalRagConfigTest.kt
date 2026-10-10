package localrag

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalRagConfigTest {
    @Test
    fun `configuration contains only local ollama model settings`() {
        val workingDirectory = Files.createTempDirectory("local-rag-config")
        val config = LocalRagConfig.load(
            environment = mapOf(
                "OLLAMA_URL" to "http://127.0.0.1:22110",
                "OLLAMA_EMBEDDING_MODEL" to "embed-local",
                "OLLAMA_CHAT_MODEL" to "chat-local",
                "INDEX_DB" to "data/test.db",
            ),
            workingDirectory = workingDirectory,
        )

        assertEquals("http://127.0.0.1:22110", config.ollamaUrl)
        assertEquals("embed-local", config.embeddingModel)
        assertEquals("chat-local", config.chatModel)
        assertTrue(config.databasePath.startsWith(workingDirectory))
    }
}
