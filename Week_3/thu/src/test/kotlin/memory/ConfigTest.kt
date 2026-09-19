package memory

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {
    @Test
    fun `environment overrides dotenv`() {
        val dotenv = Files.createTempFile("invariant-config", ".env")
        dotenv.writeText("LLM_API_KEY=file-key\nLLM_TEMPERATURE=0.7")

        val config = AppConfig.fromEnvironment(
            environment = mapOf("LLM_API_KEY" to "environment-key"),
            dotenvPath = dotenv,
        )

        assertEquals("environment-key", config.apiKey)
        assertEquals(0.7, config.temperature)
    }
}
