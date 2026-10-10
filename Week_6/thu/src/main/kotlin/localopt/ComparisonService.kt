package localopt

import java.util.concurrent.atomic.AtomicLong

interface LocalModelClient {
    suspend fun generate(prompt: String, profile: GenerationProfile): GenerationRun
    suspend fun info(): ModelInfo
}

class ComparisonService(
    private val model: LocalModelClient,
    val baselineProfile: GenerationProfile,
    val optimizedProfile: GenerationProfile,
) {
    private val comparisonNumber = AtomicLong(0)

    suspend fun compare(rawPrompt: String): ComparisonResult {
        val prompt = rawPrompt.trim()
        require(prompt.isNotEmpty()) { "Введите запрос для сравнения." }
        require(prompt.length <= 4_000) { "Запрос не должен превышать 4000 символов." }

        val profiles = if (comparisonNumber.getAndIncrement() % 2L == 0L) {
            listOf(baselineProfile, optimizedProfile)
        } else {
            listOf(optimizedProfile, baselineProfile)
        }
        val runs = profiles.associate { profile -> profile.id to model.generate(prompt, profile) }
        return ComparisonResult(
            prompt = prompt,
            executionOrder = profiles.map(GenerationProfile::id),
            baseline = runs.getValue(baselineProfile.id).compared(),
            optimized = runs.getValue(optimizedProfile.id).compared(),
        )
    }

    suspend fun info(): AppInfo = AppInfo(model.info(), baselineProfile, optimizedProfile)

    private fun GenerationRun.compared(): ComparedRun = ComparedRun(
        profile = profile,
        answer = answer,
        metrics = metrics,
        quality = answer.qualitySignals(),
    )

    private fun String.qualitySignals(): QualitySignals {
        val words = trim().split(Regex("\\s+")).count(String::isNotBlank)
        val lines = lineSequence().filter(String::isNotBlank).toList()
        return QualitySignals(
            words = words,
            concise = words <= 220,
            structured = lines.any { it.matches(Regex("\\s*(?:[-*•]|\\d+[.)]).+")) },
            briefAnswer = trimStart().startsWith("Краткий ответ:", ignoreCase = true),
        )
    }
}
