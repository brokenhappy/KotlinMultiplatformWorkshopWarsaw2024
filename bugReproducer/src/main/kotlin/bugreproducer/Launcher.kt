package bugreproducer

import kmpworkshop.client.ClientSettings
import kmpworkshop.common.Resource
import kmpworkshop.common.coroutinesToLoom
import kmpworkshop.common.resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.writeText

internal const val ReproducerReadyMarker = "BUG_REPRODUCER_READY"

/**
 * The small interaction surface that the reproduction operation needs from a UI. Implementations may suspend while
 * waiting for the user to resolve a conflict or retry a failed compilation.
 */
interface ReproducerUi {
    suspend fun showConflict(worktree: Path, prompt: String)

    suspend fun showCompilationFailure(worktree: Path, prompt: String, output: String)

    suspend fun showRunning(worktree: Path, warnings: List<String>): Nothing
}

data class HistoricalClientRunning(val worktree: Path, val warnings: List<String>)

/**
 * Owns one complete reproduction attempt. The caller owns only the coroutine that invokes this function; the
 * temporary worktree, Gradle process, conflict waits, and retryable compilation failures are all scoped to it.
 */
suspend fun runReproducer(
    report: LoadedBugReport,
    ui: ReproducerUi,
    worktrees: BugReproducerWorktreeManager = GitBugReproducerWorktreeManager(),
) {
    val diagnostics = parseReproductionDiagnostics(report.report).getOrThrow()
    preparedWorktree(diagnostics, ui, worktrees, report.path).use { prepared ->
        val apiKey = apiKeyFromAppliedClientFiles(prepared.directory).getOrThrow()
        while (true) {
            try {
                historicalClientAttempt(
                    prepared = prepared,
                    report = report,
                    settings = diagnostics.clientSettings,
                    apiKey = apiKey,
                ).use { running ->
                    ui.showRunning(running.worktree, running.warnings)
                }
            } catch (failure: CompilationFailure) {
                ui.showCompilationFailure(
                    prepared.directory,
                    compilationFailurePrompt(
                        report.path,
                        prepared.clientChangesCommit,
                        prepared.serverChangesCommit,
                        diagnostics,
                    ),
                    failure.output,
                )
            } catch (exit: HistoricalClientExited) {
                if (exit.exitCode == 0) return@use
                throw exit
            }
        }
    }
}

internal fun preparedWorktree(
    diagnostics: ParsedReproductionDiagnostics,
    ui: ReproducerUi,
    worktrees: BugReproducerWorktreeManager,
    reportPath: Path? = null,
): Resource<PreparedWorktree> = resource { consumer ->
    var retainedWorktree: Path? = null
    try {
        suspend fun prepareWorktree(): PreparedWorktree {
            var result = worktrees.prepare(diagnostics)
            while (true) {
                when (result) {
                    is WorktreePreparationResult.Failed -> error(result.message)
                    is WorktreePreparationResult.Ready -> {
                        retainedWorktree = result.prepared.directory
                        return result.prepared
                    }
                    is WorktreePreparationResult.Conflicts -> {
                        retainedWorktree = result.worktree
                        ui.showConflict(
                            result.worktree,
                            conflictPrompt(
                                paths = result.paths,
                                reportPath = reportPath,
                                diagnostics = diagnostics,
                                clientChangesCommit = result.clientChangesCommit,
                                serverChangesCommit = result.serverChangesCommit,
                            ),
                        )
                        result = worktrees.continuePreparation(result)
                    }
                }
            }
        }

        consumer(prepareWorktree())
    } finally {
        retainedWorktree?.let { worktree -> runCatching { worktrees.cleanup(worktree) } }
    }
}

private fun historicalClientAttempt(
    prepared: PreparedWorktree,
    report: LoadedBugReport,
    settings: ClientSettings,
    apiKey: String,
): Resource<HistoricalClientRunning> = resource { consumer ->
    val config = writeReproductionConfig(prepared, report, settings, apiKey)
    val gradle = if (System.getProperty("os.name").contains("windows", ignoreCase = true)) {
        prepared.directory.resolve("gradlew.bat").toString()
    } else {
        prepared.directory.resolve("gradlew").toString()
    }
    val command = listOf(
        gradle,
        "--no-daemon",
        "--console=plain",
        ":bugReproducer:run",
        "--args=--reproducer-config=${config.toAbsolutePath()}",
    )

    managedProcess(command, prepared.directory).use { process ->
        coroutineScope {
            val output = StringBuilder()
            val ready = CompletableDeferred<Unit>()
            val outputReader = launch {
                coroutinesToLoom {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            output.appendLine(line)
                            if (line.contains(ReproducerReadyMarker)) ready.complete(Unit)
                        }
                    }
                }
            }
            val processExit = async {
                coroutinesToLoom { process.waitFor() }
            }
            val monitor = launch {
                val exitCode = processExit.await()
                outputReader.join()
                if (!ready.completeExceptionally(CompilationFailure(output.toString()))) {
                    throw HistoricalClientExited(exitCode, output.toString())
                }
            }
            try {
                ready.await()
                consumer(HistoricalClientRunning(prepared.directory, prepared.warnings))
            } finally {
                terminateProcessTree(process)
                monitor.cancel()
                outputReader.cancel()
                processExit.cancel()
            }
        }
    }
}

private fun managedProcess(command: List<String>, worktree: Path): Resource<Process> = resource { consumer ->
    val process = coroutinesToLoom {
        ProcessBuilder(command)
            .directory(worktree.toFile())
            .redirectErrorStream(true)
            .start()
    }
    process.outputStream.close()
    try {
        consumer(process)
    } finally {
        terminateProcessTree(process)
    }
}

private fun writeReproductionConfig(
    prepared: PreparedWorktree,
    report: LoadedBugReport,
    settings: ClientSettings,
    apiKey: String,
): Path {
    val configPath = prepared.directory.resolve(".bug-reproducer-config.json")
    configPath.writeText(
        Json { prettyPrint = true }.encodeToString(
            ReproductionConfig(
                report = report.report,
                settings = ReproductionSettings(settings.zoom),
                worktree = prepared.directory.toString(),
                apiKey = apiKey,
            ),
        ),
    )
    return configPath
}

private class CompilationFailure(val output: String) : RuntimeException(
    "The historical client did not compile or start.",
)

private class HistoricalClientExited(val exitCode: Int, output: String) : RuntimeException(
    "The historical client exited after starting with code $exitCode.\n$output",
)

private val ReproductionWorktreeExplanation = listOf(
    "This worktree is an attempt to reproduce a reported bug. The client, server, and shared application sources " +
        "come from the reported revisions.",
    "The report's captured client and server changes are already applied here as synthetic Git commits on top of " +
        "those source revisions. They are reported application changes, including changes in whichever application " +
        "files the report captured.",
    "The current checkout's bugReproducer module and root Gradle configuration were inserted as reproducer " +
        "scaffolding. They are support code for launching this worktree, not part of the reported application state.",
).joinToString("\n\n")

private fun reproductionStateExplanation(
    diagnostics: ParsedReproductionDiagnostics?,
    clientChangesCommit: String?,
    serverChangesCommit: String?,
): String = buildString {
    append("The bug we're trying to reproduce had the following states:")
    append("\n\n")
    if (diagnostics == null) {
        append("The report's client and server Git state was not available to this prompt.")
        return@buildString
    }
    if (diagnostics.clientCommit.equals(diagnostics.serverCommit, ignoreCase = true)) {
        append("The client and server both had commit `").append(diagnostics.clientCommit).append("` checked out.")
    } else {
        append("The server had commit `").append(diagnostics.serverCommit).append("` checked out.")
        append("\nThe client had commit `").append(diagnostics.clientCommit).append("` checked out.")
    }
    append("\n\n")
    if (diagnostics.clientLocalChanges.isBlank()) {
        append("The client had no captured local changes.")
    } else {
        append("Client captured changes commit: `")
            .append(clientChangesCommit ?: "unavailable")
            .append("`. This synthetic commit contains the report's captured client local changes.")
    }
    append("\n")
    if (diagnostics.serverChanges.isBlank() && diagnostics.serverUntrackedChanges.isBlank()) {
        append("The server had no captured changes.")
    } else {
        append("Server captured changes commit: `")
            .append(serverChangesCommit ?: "unavailable")
            .append("`. This synthetic commit contains the report's captured server changes.")
    }
}

private fun reproductionEvidence(reportPath: Path?): List<String> = buildList {
    reportPath?.let {
        add("The user-reported bug this worktree is intended to reproduce can be found in: $it")
    }
}

private fun conflictPrompt(
    paths: List<String>,
    reportPath: Path?,
    diagnostics: ParsedReproductionDiagnostics?,
    clientChangesCommit: String?,
    serverChangesCommit: String?,
): String = buildString {
    append(reproductionStateExplanation(diagnostics, clientChangesCommit, serverChangesCommit))
    reproductionEvidence(reportPath).forEach {
        append("\n\n").append(it)
    }
    append("\n\n").append(ReproductionWorktreeExplanation)
    append(
        "\n\nYour task is to resolve every conflict listed below and finish the rebase so the launcher can build and run " +
            "the reproduction. Edit the conflicted files, stage each resolved file (or remove it if the resolution " +
            "deletes it), and run `git rebase --continue`. If Git stops again, resolve and continue again until the " +
            "rebase completes. Do not stop after merely removing conflict markers; leave no unmerged paths.",
    )
    append("\n\nGit is rebasing the combined client and server states, and the rebase stopped on these conflicts:")
    paths.forEach { append("\n- ").append(it) }
}

internal fun compilationFailurePrompt(
    reportPath: Path? = null,
    clientChangesCommit: String? = null,
    serverChangesCommit: String? = null,
    diagnostics: ParsedReproductionDiagnostics? = null,
): String = buildString {
    append(reproductionStateExplanation(diagnostics, clientChangesCommit, serverChangesCommit))
    reproductionEvidence(reportPath).forEach {
        append("\n\n").append(it)
    }
    append("\n\n").append(ReproductionWorktreeExplanation)
    append("\n\nThis Codex session was opened because the reproduction build failed.")
    if (clientChangesCommit != null || serverChangesCommit != null) {
        append(
            " Start by inspecting the captured changes commits: they contain the report's local application edits and " +
                "are the most likely cause of this build failure. If they do not explain it, check the historical source, " +
                "inserted Gradle/reproducer scaffolding, and the toolchain or dependency environment.",
        )
    }
    append(
        "\n\nRun Gradle's :bugReproducer:run task to reproduce and diagnose the failure. " +
            "The reproduction build must pass so the historical client can start.",
    )
    append(
        " You may edit the historical application code or the inserted Gradle and bugReproducer scaffolding " +
            "when necessary; make the smallest fix that makes the build pass and preserve the reported behavior otherwise.",
    )
}
