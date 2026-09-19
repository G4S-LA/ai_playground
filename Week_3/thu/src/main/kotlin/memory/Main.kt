package memory

fun main(args: Array<String>) {
    val demo = "--demo" in args
    val command = args.firstOrNull { !it.startsWith("--") } ?: "cli"
    val config = AppConfig.fromEnvironment(demo)
    val store = FileMemoryStore(config.dataDirectory)
    val model: LanguageModel = if (demo) DemoLanguageModel() else OpenAiCompatibleModel(config)
    val guard: InvariantGuard = if (demo) DemoInvariantGuard() else LlmInvariantGuard(model)
    val agent = MemoryAgent(store, model, guard, config.systemPrompt, config.model)

    when (command) {
        "cli" -> MemoryCli(agent).run()
        "web" -> runWeb(agent, config)
        else -> error("Неизвестный режим '$command'. Используйте cli или web.")
    }
}
