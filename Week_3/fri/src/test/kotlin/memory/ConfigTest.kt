package memory

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigTest {
    @Test
    fun `loads quoted and unquoted values from dotenv`() {
        val directory = Files.createTempDirectory("memory-config-test")
        val dotenv = directory.resolve(".env")
        dotenv.writeText(
            """
            LLM_API_KEY="file-key"
            LLM_MODEL=file-model
            WEB_PORT='9090'
            """.trimIndent()
        )

        val config = AppConfig.fromEnvironment(environment = emptyMap(), dotenvPath = dotenv)

        assertEquals("file-key", config.apiKey)
        assertEquals("file-model", config.model)
        assertEquals(9090, config.webPort)
    }

    @Test
    fun `process environment has priority over dotenv`() {
        val dotenv = Files.createTempFile("memory-config-test", ".env")
        dotenv.writeText("LLM_API_KEY=file-key\nLLM_MODEL=file-model")

        val config = AppConfig.fromEnvironment(
            environment = mapOf("LLM_API_KEY" to "environment-key"),
            dotenvPath = dotenv,
        )

        assertEquals("environment-key", config.apiKey)
        assertEquals("file-model", config.model)
    }

    @Test
    fun `reports missing key when environment and dotenv are empty`() {
        val missing = Files.createTempDirectory("memory-config-test").resolve("missing.env")
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(environment = emptyMap(), dotenvPath = missing)
        }
    }
}
