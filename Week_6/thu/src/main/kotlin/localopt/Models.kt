package localopt

data class GenerationProfile(
    val id: String,
    val label: String,
    val temperature: Double,
    val maxTokens: Int,
    val contextWindow: Int,
    val systemPrompt: String,
)

data class ResourceSnapshot(
    val loadedBytes: Long? = null,
    val vramBytes: Long? = null,
    val runnerContextLength: Int? = null,
)

data class GenerationMetrics(
    val totalMs: Long,
    val loadMs: Long,
    val promptEvalMs: Long,
    val generationMs: Long,
    val promptTokens: Int,
    val responseTokens: Int,
    val tokensPerSecond: Double,
    val resources: ResourceSnapshot,
)

data class GenerationRun(
    val profile: GenerationProfile,
    val answer: String,
    val metrics: GenerationMetrics,
)

data class QualitySignals(
    val words: Int,
    val concise: Boolean,
    val structured: Boolean,
    val briefAnswer: Boolean,
)

data class ComparedRun(
    val profile: GenerationProfile,
    val answer: String,
    val metrics: GenerationMetrics,
    val quality: QualitySignals,
)

data class ComparisonResult(
    val prompt: String,
    val executionMode: String = "sequential",
    val executionOrder: List<String>,
    val baseline: ComparedRun,
    val optimized: ComparedRun,
)

data class ModelInfo(
    val provider: String = "Ollama",
    val endpoint: String,
    val model: String,
    val reachable: Boolean,
    val installed: Boolean,
    val parameterSize: String? = null,
    val quantization: String? = null,
    val modelBytes: Long? = null,
    val availableModels: List<String> = emptyList(),
    val error: String? = null,
)

data class AppInfo(
    val model: ModelInfo,
    val baseline: GenerationProfile,
    val optimized: GenerationProfile,
)

data class CompareRequest(val prompt: String = "")
data class ErrorResponse(val error: String)
