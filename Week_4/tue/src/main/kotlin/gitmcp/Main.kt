package gitmcp

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

public fun main(args: Array<String>) {
    KotlinLoggingConfiguration.logStartupMessage = false

    runBlocking {
        if (args.firstOrNull() == "server") {
            runGitMcpServer()
            return@runBlocking
        }

        val repository = args.firstOrNull()
            ?: Path.of("../..").toAbsolutePath().normalize().toString()
        val result = GitAgent().inspectRepository(repository)

        println("Agent called MCP tool '$GIT_SUMMARY_TOOL'")
        println("Agent received and used the result:")
        println(result)
    }
}
