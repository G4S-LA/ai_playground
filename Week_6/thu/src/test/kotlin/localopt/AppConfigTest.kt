package localopt

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppConfigTest {
    @Test
    fun `loads two generation profiles for one local model`() {
        val config = AppConfig.load(
            environment = mapOf(
                "OLLAMA_MODEL" to "same-local-model",
                "BASELINE_TEMPERATURE" to "0.8",
                "BASELINE_MAX_TOKENS" to "900",
                "BASELINE_CONTEXT_WINDOW" to "8000",
                "OPTIMIZED_TEMPERATURE" to "0.15",
                "OPTIMIZED_MAX_TOKENS" to "300",
                "OPTIMIZED_CONTEXT_WINDOW" to "4000",
            ),
            workingDirectory = Files.createTempDirectory("local-opt-config"),
        )

        assertEquals("same-local-model", config.model)
        assertEquals(0.8, config.baseline.temperature)
        assertEquals(900, config.baseline.maxTokens)
        assertEquals(8000, config.baseline.contextWindow)
        assertEquals(0.15, config.optimized.temperature)
        assertEquals(300, config.optimized.maxTokens)
        assertEquals(4000, config.optimized.contextWindow)
        assertTrue(config.baseline.systemPrompt.isEmpty())
        assertTrue(config.optimized.systemPrompt.contains("Краткий ответ:"))
        assertTrue(config.optimized.systemPrompt.contains("Ограничения:"))
    }
}
