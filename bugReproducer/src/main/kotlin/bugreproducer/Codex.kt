package bugreproducer

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val DefaultBundledCodex = Path.of(
    "/Applications/ChatGPT.app/Contents/Resources/codex",
)

/**
 * Opens Codex with [worktree] as its workspace. The ChatGPT Desktop command is preferred when it is installed;
 * jbcentral remains useful on other machines and when the Desktop command cannot be started.
 */
internal fun openCodexSession(
    worktree: Path,
    bundledCodex: Path = DefaultBundledCodex,
    startProcess: (List<String>, Path) -> Unit = ::startProcess,
) {
    require(Files.isDirectory(worktree)) { "The reproduction worktree does not exist: $worktree" }

    val commands = buildList {
        if (Files.isExecutable(bundledCodex)) {
            add(listOf(bundledCodex.toString(), "app", worktree.toAbsolutePath().toString()))
        }
        add(listOf("jbcentral", "run", "codex"))
    }
    val failures = mutableListOf<Throwable>()
    commands.forEach { command ->
        try {
            startProcess(command, worktree)
            return
        } catch (failure: IOException) {
            failures += failure
        } catch (failure: SecurityException) {
            failures += failure
        }
    }

    val attempted = commands.joinToString(" or ") { it.joinToString(" ") }
    val cause = failures.lastOrNull()
    throw IllegalStateException(
        "Could not start Codex. Tried: $attempted. " +
            (cause?.message ?: "No launch command was available."),
        cause,
    )
}

private fun startProcess(command: List<String>, worktree: Path) {
    ProcessBuilder(command)
        .directory(worktree.toFile())
        .start()
}
