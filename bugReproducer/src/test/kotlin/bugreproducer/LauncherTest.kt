package bugreproducer

import kmpworkshop.client.ClientSettings
import kmpworkshop.common.ClientBugDiagnostics
import kmpworkshop.common.ClientBugReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import workshop.adminaccess.ServerBugDiagnostics
import workshop.adminaccess.ServerState
import workshop.adminaccess.StoredClientBugReport
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class LauncherTest {
    @Test
    fun `preparation rechecks conflicts and cleans the worktree after use`() = runTest {
        val worktree = Path.of("fake-worktree")
        val manager = FakeWorktreeManager(
            WorktreePreparationResult.Conflicts(
                worktree = worktree,
                paths = listOf("common/src/shared.txt"),
                warnings = listOf("warning"),
            ),
            recheckedPaths = emptyList(),
        )
        val ui = RecordingReproducerUi()

        val prepared = preparedWorktree(diagnostics(), ui, manager, Path.of("report.json")).use { it }

        assertEquals(PreparedWorktree(worktree, listOf("warning")), prepared)
        assertEquals(1, manager.prepareCalls)
        assertEquals(listOf(worktree), manager.recheckedWorktrees)
        assertEquals(listOf(worktree), manager.cleanedWorktrees)
        assertTrue(ui.conflictPrompts.single().contains("- common/src/shared.txt"))
        assertTrue(ui.conflictPrompts.single().contains("attempt to reproduce a reported bug"))
        assertTrue(ui.conflictPrompts.single().contains("client, server, and shared application sources"))
        assertTrue(ui.conflictPrompts.single().contains("bugReproducer module"))
        assertTrue(ui.conflictPrompts.single().contains("root Gradle configuration"))
        assertTrue(ui.conflictPrompts.single().contains("The user-reported bug this worktree is intended to reproduce can be found in: report.json"))
        assertTrue(ui.conflictPrompts.single().contains("The bug we're trying to reproduce had the following states:"))
        assertTrue(ui.conflictPrompts.single().contains("The server had commit `bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb` checked out."))
        assertTrue(ui.conflictPrompts.single().contains("The client had commit `aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` checked out."))
        assertTrue(ui.conflictPrompts.single().contains("The client had no captured local changes."))
        assertTrue(ui.conflictPrompts.single().contains("The server had no captured changes."))
        assertTrue(ui.conflictPrompts.single().contains("Your task is to resolve every conflict listed below"))
        assertTrue(ui.conflictPrompts.single().contains("run `git rebase --continue`"))
        assertTrue(ui.conflictPrompts.single().contains("leave no unmerged paths"))
        assertTrue(ui.conflictPrompts.single().contains("Git is rebasing"))
        assertTrue(!ui.conflictPrompts.single().contains("Worktree:"))
    }

    @Test
    fun `build failure prompt explains the inserted reproduction setup and module`() {
        val prompt = compilationFailurePrompt(
            reportPath = Path.of("report.json"),
            clientChangesCommit = "client-commit",
            serverChangesCommit = "server-commit",
            diagnostics = diagnostics().copy(
                clientLocalChanges = "client patch",
                serverChanges = "server patch",
            ),
        )

        assertTrue(prompt.contains("attempt to reproduce a reported bug"))
        assertTrue(prompt.contains("client, server, and shared application sources"))
        assertTrue(prompt.contains("bugReproducer module"))
        assertTrue(prompt.contains("root Gradle configuration"))
        assertTrue(!prompt.contains("\nreported"))
        assertTrue(!prompt.contains("\nlaunched"))
        assertTrue(!prompt.contains("\n    "))
        assertTrue(prompt.contains("This Codex session was opened because the reproduction build failed."))
        assertTrue(prompt.contains("Run Gradle's :bugReproducer:run task to reproduce and diagnose the failure."))
        assertTrue(prompt.contains("The reproduction build must pass"))
        assertTrue(prompt.contains("You may edit the historical application code"))
        assertTrue(prompt.contains("The user-reported bug this worktree is intended to reproduce can be found in: report.json"))
        assertTrue(prompt.contains("The bug we're trying to reproduce had the following states:"))
        assertTrue(prompt.contains("The server had commit `bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb` checked out."))
        assertTrue(prompt.contains("The client had commit `aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` checked out."))
        assertTrue(prompt.contains("Client captured changes commit: `client-commit`"))
        assertTrue(prompt.contains("Server captured changes commit: `server-commit`"))
        assertTrue(!prompt.contains("Client source commit:"))
        assertTrue(!prompt.contains("Server source commit:"))
        assertTrue(prompt.contains("captured client local changes"))
        assertTrue(prompt.contains("captured server changes"))
        assertTrue(prompt.contains("Start by inspecting the captured changes commits"))
        assertTrue(prompt.contains("are the most likely cause of this build failure"))
        assertTrue(prompt.contains("toolchain or dependency environment"))
        assertTrue(!prompt.contains("Do not assume they are the cause"))
        assertTrue(!prompt.contains("build failure shown below"))
        assertTrue(!prompt.contains("unmerged"))
    }

    @Test
    fun `cancellation while waiting for conflict still cleans the worktree`() = runTest {
        val worktree = Path.of("fake-worktree")
        val manager = FakeWorktreeManager(
            WorktreePreparationResult.Conflicts(
                worktree = worktree,
                paths = listOf("common/src/shared.txt"),
                warnings = emptyList(),
            ),
            recheckedPaths = emptyList(),
        )
        val conflictShown = CompletableDeferred<Unit>()
        val ui = RecordingReproducerUi {
            conflictShown.complete(Unit)
            awaitCancellation()
        }

        val preparation = launch {
            preparedWorktree(diagnostics(), ui, manager).use { awaitCancellation() }
        }
        conflictShown.await()
        preparation.cancelAndJoin()

        assertEquals(listOf(worktree), manager.cleanedWorktrees)
        assertEquals(emptyList(), manager.recheckedWorktrees)
    }

    @Test
    fun `a historical client that exits successfully after readiness is not reported as an error`() = runTest {
        val worktree = java.nio.file.Files.createTempDirectory("bug-reproducer-fake-worktree")
        try {
            worktree.resolve("common/src/main/kotlin/kmpworkshop/common/Secrets.kt").also {
                it.parent.createDirectories()
                it.writeText("val clientApiKey: String? = \"test-key\"")
            }
            worktree.resolve("gradlew").also {
                it.writeText("#!/bin/sh\nprintf 'BUG_REPRODUCER_READY\\n'\nexit 0\n")
                it.toFile().setExecutable(true)
            }
            val manager = FakeWorktreeManager(
                WorktreePreparationResult.Ready(PreparedWorktree(worktree, emptyList())),
                recheckedPaths = emptyList(),
            )
            val ui = RecordingReproducerUi()

            runReproducer(report(), ui, manager)

            assertEquals(worktree, ui.runningWorktree)
            assertEquals(listOf(worktree), manager.cleanedWorktrees)
        } finally {
            worktree.toFile().deleteRecursively()
        }
    }

    private fun diagnostics() = ParsedReproductionDiagnostics(
        clientCommit = "a".repeat(40),
        serverCommit = "b".repeat(40),
        clientLocalChanges = "",
        serverChanges = "",
        serverUntrackedChanges = "",
        clientSettings = ClientSettings(),
        warnings = emptyList(),
    )

    private fun report() = LoadedBugReport(
        path = Path.of("report.json"),
        report = StoredClientBugReport(
            clientReport = ClientBugReport(
                description = "test report",
                createdAt = Clock.System.now(),
                diagnostics = ClientBugDiagnostics(
                    values = mapOf(
                        "client.git.checkedOutCommit" to "a".repeat(40),
                        "client.git.localChanges" to "",
                        "client.settings" to "zoom=1",
                    ),
                ),
            ),
            serverDiagnostics = ServerBugDiagnostics(
                values = mapOf(
                    "server.checkedOutCommit" to "b".repeat(40),
                    "server.changes" to "",
                    "server.untrackedChanges" to "",
                ),
                failures = emptyList(),
            ),
            serverState = ServerState(),
            receivedAt = Clock.System.now(),
        ),
    )
}

private class RecordingReproducerUi(
    private val onConflict: suspend (String) -> Unit = {},
) : ReproducerUi {
    val conflictPrompts = mutableListOf<String>()
    var runningWorktree: Path? = null

    override suspend fun showConflict(worktree: Path, prompt: String) {
        conflictPrompts.add(prompt)
        onConflict(prompt)
    }

    override suspend fun showCompilationFailure(worktree: Path, prompt: String, output: String) =
        error("Unexpected compilation failure in $worktree: $output")

    override suspend fun showRunning(worktree: Path, warnings: List<String>): Nothing {
        runningWorktree = worktree
        awaitCancellation()
    }
}

private class FakeWorktreeManager(
    private val preparation: WorktreePreparationResult,
    private val recheckedPaths: List<String>,
) : BugReproducerWorktreeManager {
    var prepareCalls = 0
    val recheckedWorktrees = mutableListOf<Path>()
    val cleanedWorktrees = mutableListOf<Path>()

    override suspend fun prepare(diagnostics: ParsedReproductionDiagnostics): WorktreePreparationResult {
        prepareCalls++
        return preparation
    }

    override suspend fun continuePreparation(conflicts: WorktreePreparationResult.Conflicts): WorktreePreparationResult {
        val worktree = conflicts.worktree
        recheckedWorktrees.add(worktree)
        return if (recheckedPaths.isEmpty()) {
            WorktreePreparationResult.Ready(
                PreparedWorktree(worktree, conflicts.warnings, conflicts.clientCommit, conflicts.serverCommit),
            )
        } else {
            conflicts.copy(paths = recheckedPaths)
        }
    }

    override suspend fun cleanup(worktree: Path) {
        cleanedWorktrees.add(worktree)
    }
}
