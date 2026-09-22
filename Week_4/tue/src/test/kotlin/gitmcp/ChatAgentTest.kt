package gitmcp

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ChatAgentTest {
    @Test
    fun `agent calls MCP tool and uses result in model answer`(): Unit = runBlocking {
        val repository = createTempDirectory("git-mcp-agent-test-")
        val chatData = createTempDirectory("git-mcp-chat-test-")
        try {
            git(repository, "init", "-q")
            git(repository, "config", "user.name", "MCP Test")
            git(repository, "config", "user.email", "mcp@example.test")
            Files.writeString(repository.resolve("README.md"), "# Test repository\n")
            git(repository, "add", "README.md")
            git(repository, "commit", "-q", "-m", "Initial commit")

            val agent = ChatAgent(
                model = DemoLanguageModel(),
                tools = GitMcpGateway(),
                store = FileChatStore(chatData),
                systemPrompt = "You are a test agent.",
                repositoryPath = repository.toString(),
                modelName = "demo-test",
            )
            val session = agent.createSession()
            val reply = agent.reply(session.id, "What changed in the repository?")

            assertEquals(listOf(GIT_SUMMARY_TOOL), reply.toolExecutions.map { it.name })
            assertContains(reply.toolExecutions.single().result, "Initial commit")
            assertContains(reply.answer, "Initial commit")
            assertEquals(2, agent.snapshot(session.id).session.messages.size)
        } finally {
            repository.toFile().deleteRecursively()
            chatData.toFile().deleteRecursively()
        }
    }

    private fun git(repository: Path, vararg arguments: String) {
        val process = ProcessBuilder(
            listOf("git", "-C", repository.toString()) + arguments,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { output }
    }
}
