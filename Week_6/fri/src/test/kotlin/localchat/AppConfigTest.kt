package localchat

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigTest {
    @Test
    fun `loads network and storage settings without a required model`() {
        val directory = Files.createTempDirectory("private-chat-config")
        val config = AppConfig.load(
            environment = mapOf(
                "SERVICE_API_KEY" to "a-secure-test-key-with-24-chars",
                "SERVICE_PORT" to "9090",
                "CHAT_DATABASE" to "state/chats.db",
            ),
            workingDirectory = directory,
        )

        assertEquals("0.0.0.0", config.host)
        assertEquals(9090, config.port)
        assertEquals(directory.resolve("state/chats.db"), config.databasePath)
        assertNull(config.model)
    }

    @Test
    fun `init creates utf8 dotenv with a random key and automatic model selection`() {
        val path = Files.createTempDirectory("private-chat-init").resolve(".env")

        AppConfig.initializeDotenv(path)

        val content = path.readText(Charsets.UTF_8)
        AppConfig.initializeDotenv(path)
        assertTrue(content.contains("SERVICE_API_KEY="))
        assertTrue(content.contains("OLLAMA_MODEL=\n"))
        assertTrue(content.contains("CHAT_DATABASE=data/local-chat.db"))
        assertEquals(content, path.readText(Charsets.UTF_8))
    }
}
