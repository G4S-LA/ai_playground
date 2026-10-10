package localopt

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComparisonServiceTest {
    @Test
    fun `runs profiles sequentially and alternates order`() = runBlocking {
        val calls = mutableListOf<String>()
        val model = object : LocalModelClient {
            override suspend fun generate(prompt: String, profile: GenerationProfile): GenerationRun {
                calls += profile.id
                val answer = if (profile.id == "optimized") {
                    "Краткий ответ:\nКороткий вывод.\n\nДействия:\n1. Первый шаг.\n2. Второй шаг."
                } else {
                    "Обычный ответ без списка"
                }
                return GenerationRun(profile, answer, metrics())
            }

            override suspend fun info() = modelInfo()
        }
        val service = ComparisonService(model, baseline(), optimized())

        val first = service.compare("Как настроить модель?")
        val second = service.compare("Как настроить модель?")

        assertEquals(listOf("baseline", "optimized"), first.executionOrder)
        assertEquals(listOf("optimized", "baseline"), second.executionOrder)
        assertEquals(listOf("baseline", "optimized", "optimized", "baseline"), calls)
        assertFalse(first.baseline.quality.structured)
        assertFalse(first.baseline.quality.briefAnswer)
        assertTrue(first.optimized.quality.structured)
        assertTrue(first.optimized.quality.briefAnswer)
    }
}

internal fun baseline() = GenerationProfile("baseline", "До", 0.7, 1024, 8192, "Общий prompt")
internal fun optimized() = GenerationProfile("optimized", "После", 0.1, 384, 4096, "Точный prompt")
internal fun metrics() = GenerationMetrics(1200, 10, 50, 1000, 40, 120, 120.0, ResourceSnapshot())
internal fun modelInfo() = ModelInfo(
    endpoint = "http://127.0.0.1:11434",
    model = "qwen-test",
    reachable = true,
    installed = true,
    parameterSize = "3.1B",
    quantization = "Q4_K_M",
    modelBytes = 1_900_000_000,
)
