package gitmcp

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.runBlocking

public fun main(args: Array<String>) {
    KotlinLoggingConfiguration.logStartupMessage = false

    if (args.firstOrNull() == "server") {
        runBlocking {
            runGitMcpServer()
        }
        return
    }

    val demo = "--demo" in args
    val command = args.firstOrNull { !it.startsWith("--") } ?: "cli"
    val config = AppConfig.fromEnvironment(demo)
    val model: LanguageModel = if (demo) DemoLanguageModel() else OpenAiCompatibleModel(config)
    val agent = ChatAgent(
        model = model,
        tools = GitMcpGateway(),
        store = FileChatStore(config.dataDirectory),
        systemPrompt = config.systemPrompt,
        repositoryPath = config.repositoryPath,
        modelName = config.model,
    )

    when (command) {
        "cli" -> ChatCli(agent).run()
        "web" -> runWeb(agent, config)
        else -> error("Unknown mode '$command'. Use cli or web.")
    }
}
