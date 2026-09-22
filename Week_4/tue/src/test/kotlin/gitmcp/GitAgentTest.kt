package gitmcp

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains

class GitAgentTest {
    @Test
    fun `agent calls MCP Git tool and uses its result`(): Unit = runBlocking {
        val repository = createTempDirectory("git-mcp-test-")
        try {
            git(repository, "init", "-q")
            git(repository, "config", "user.name", "MCP Test")
            git(repository, "config", "user.email", "mcp@example.test")
            Files.writeString(repository.resolve("README.md"), "# Test repository\n")
            git(repository, "add", "README.md")
            git(repository, "commit", "-q", "-m", "Initial commit")

            val result = GitAgent().inspectRepository(repository.toString(), commitLimit = 1)

            assertContains(result, "Repository:")
            assertContains(result, "Status:")
            assertContains(result, "Initial commit")
        } finally {
            repository.toFile().deleteRecursively()
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
