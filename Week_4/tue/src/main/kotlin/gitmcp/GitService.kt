package gitmcp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class GitService {
    fun repositorySummary(repositoryPath: String, commitLimit: Int): String {
        require(commitLimit in 1..20) { "commitLimit must be between 1 and 20" }

        val path = Path.of(repositoryPath).toAbsolutePath().normalize()
        require(Files.isDirectory(path)) { "Repository directory does not exist: $path" }

        val root = git(path, "rev-parse", "--show-toplevel").requireSuccess("Not a Git repository")
        val status = git(path, "status", "--short", "--branch")
            .requireSuccess("Cannot read repository status")
        val log = git(
            path,
            "log",
            "-$commitLimit",
            "--pretty=format:%h | %an | %s",
        ).requireSuccess("Cannot read commit history")

        return buildString {
            appendLine("Repository: $root")
            appendLine("Status:")
            appendLine(status.ifBlank { "Clean working tree" })
            appendLine("Recent commits:")
            append(log.ifBlank { "No commits" })
        }
    }

    private fun git(repository: Path, vararg arguments: String): CommandResult {
        val process = ProcessBuilder(
            listOf("git", "-C", repository.toString()) + arguments,
        ).redirectErrorStream(true).start()

        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return CommandResult(exitCode = -1, output = "Git command timed out")
        }

        return CommandResult(
            exitCode = process.exitValue(),
            output = process.inputStream.bufferedReader().use { it.readText().trim() },
        )
    }
}

private data class CommandResult(
    val exitCode: Int,
    val output: String,
) {
    fun requireSuccess(message: String): String {
        check(exitCode == 0) { "$message: $output" }
        return output
    }
}
