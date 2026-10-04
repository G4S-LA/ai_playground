package ragagent

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RagConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loads quoted and unquoted settings from dotenv`() {
        val dotenv = tempDir.resolve(".env")
        dotenv.writeText(
            """
            # External generation
            LLM_API_KEY="file-key"
            LLM_MODEL=api-model
            WEB_PORT='9090'
            export LLM_PROVIDER=api
            """.trimIndent(),
        )

        val config = RagConfig.fromEnvironment(environment = emptyMap(), dotenvPath = dotenv)

        assertEquals("file-key", config.apiKey)
        assertEquals("api-model", config.apiModel)
        assertEquals("api", config.llmProvider)
        assertEquals(9090, config.port)
    }

    @Test
    fun `process environment has priority over dotenv`() {
        val dotenv = tempDir.resolve(".env")
        dotenv.writeText("LLM_API_KEY=file-key\nLLM_MODEL=file-model")

        val config = RagConfig.fromEnvironment(
            environment = mapOf("LLM_API_KEY" to "environment-key"),
            dotenvPath = dotenv,
        )

        assertEquals("environment-key", config.apiKey)
        assertEquals("file-model", config.apiModel)
    }

    @Test
    fun `loads missing model settings from fallback dotenv without inheriting its port`() {
        val primary = tempDir.resolve("local.env")
        val fallback = tempDir.resolve("previous.env")
        primary.writeText("LLM_MODEL=local-model")
        fallback.writeText("LLM_API_KEY=previous-key\nLLM_MODEL=previous-model\nWEB_PORT=8080")

        val config = RagConfig.fromEnvironment(
            environment = emptyMap(),
            dotenvPath = primary,
            defaultPort = 5000,
            fallbackDotenvPaths = listOf(fallback),
            fallbackDotenvKeys = setOf("LLM_API_KEY", "LLM_MODEL"),
        )

        assertEquals("previous-key", config.apiKey)
        assertEquals("local-model", config.apiModel)
        assertEquals(5000, config.port)
    }

    @Test
    fun `missing dotenv uses defaults`() {
        val config = RagConfig.fromEnvironment(
            environment = emptyMap(),
            dotenvPath = tempDir.resolve("missing.env"),
        )

        assertEquals(null, config.apiKey)
        assertEquals("api", config.llmProvider)
        assertEquals("deepseek-chat", config.apiModel)
        assertEquals(8080, config.port)
    }

    @Test
    fun `rejects malformed dotenv line`() {
        val dotenv = tempDir.resolve(".env")
        dotenv.writeText("THIS IS NOT AN ASSIGNMENT")

        assertFailsWith<IllegalArgumentException> {
            RagConfig.fromEnvironment(environment = emptyMap(), dotenvPath = dotenv)
        }
    }
}
