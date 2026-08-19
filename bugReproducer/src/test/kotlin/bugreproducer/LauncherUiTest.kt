package bugreproducer

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kmpworkshop.common.ClientBugDiagnostics
import kmpworkshop.common.ClientBugReport
import kotlinx.coroutines.CompletableDeferred
import workshop.adminaccess.ServerBugDiagnostics
import workshop.adminaccess.ServerState
import workshop.adminaccess.StoredClientBugReport
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class LauncherUiTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selects a report and displays its details`() = runComposeUiTest {
        val first = loaded("first", "first description")
        val second = loaded("second", "second description")
        setContent {
            MaterialTheme {
                LauncherApp(BugReportLoadResult(listOf(first, second), emptyList()), onExit = {})
            }
        }

        onNodeWithTag("report-description").assertIsDisplayed()
        onNodeWithTag("report-row-second.json").performClick()
        onNodeWithTag("report-description").assertIsDisplayed()
        onNodeWithTag("revert-and-quit-button").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `report details offers buttons for captured client and server changes`() = runComposeUiTest {
        val report = loaded(
            name = "changes",
            description = "changes description",
            clientChanges = "client patch",
            serverChanges = "server patch",
            serverUntrackedChanges = "untracked server patch",
        )
        setContent {
            MaterialTheme {
                LauncherApp(BugReportLoadResult(listOf(report), emptyList()), onExit = {})
            }
        }

        onNodeWithTag("copy-client-changes-button").assertIsDisplayed()
        onNodeWithTag("copy-server-changes-button").assertIsDisplayed()
        onNodeWithText("Copy client changes").assertIsDisplayed()
        onNodeWithText("Copy server changes").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `renders launch errors inside a selectable container`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SelectableLaunchError("The historical client could not be compiled: see full details here")
            }
        }

        onNodeWithTag("launch-error").assertIsDisplayed()
        onNodeWithTag("launch-error-scroll").assertIsDisplayed()
        onNodeWithTag("launch-error-selection").assertIsDisplayed()
        onNodeWithTag("copy-launch-error-button").assertIsDisplayed()
        onNodeWithText("Copy error").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `renders started status in a selectable container`() = runComposeUiTest {
        var canceled = false
        setContent {
            MaterialTheme {
                RunningReproductionStatus(
                    worktree = Path.of("/tmp/bug-reproducer-worktree"),
                    warnings = listOf("Warning details"),
                    onCancel = { canceled = true },
                )
            }
        }

        onNodeWithTag("started-status-selection").assertIsDisplayed()
        onNodeWithText("Warning details").assertIsDisplayed()
        onNodeWithTag("cancel-reproduction-button").assertIsDisplayed()
        onNodeWithText("Cancel reproduction").performClick()
        assertTrue(canceled)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `renders cancel action during conflict resolution`() = runComposeUiTest {
        var canceled = false
        setContent {
            MaterialTheme {
                ConflictReproductionStatus(
                    worktree = Path.of("/tmp/bug-reproducer-worktree"),
                    prompt = "Resolve this conflict",
                    onOpenCodexSession = {},
                    onResolved = {},
                    onCancel = { canceled = true },
                )
            }
        }

        onNodeWithTag("cancel-reproduction-button").assertIsDisplayed()
        onNodeWithText("Cancel reproduction").performClick()
        assertTrue(canceled)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `offers a Codex session for a reproduction worktree`() = runComposeUiTest {
        val worktree = Path.of("/tmp/bug-reproducer-worktree")
        var opened: Path? = null
        setContent {
            MaterialTheme {
                OpenCodexSessionButton(worktree) { opened = it }
            }
        }

        onNodeWithTag("open-codex-session-button").assertIsDisplayed().performClick()

        assertEquals(worktree, opened)
        onNodeWithText(OpenCodexSessionButtonLabel).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `renders a copy prompt button for build failures`() = runComposeUiTest {
        var canceled = false
        setContent {
            MaterialTheme {
                CompilationFailureStatus(
                    worktree = Path.of("/tmp/bug-reproducer-worktree"),
                    prompt = compilationFailurePrompt(),
                    output = "Gradle compilation output",
                    retry = CompletableDeferred(),
                    onOpenCodexSession = {},
                    onCancel = { canceled = true },
                )
            }
        }

        onNodeWithTag("build-error-prompt").assertIsDisplayed()
        onNodeWithTag("build-error-prompt-panel").assertIsDisplayed()
        onNodeWithTag("build-error-prompt-scroll").assertIsDisplayed()
        onNodeWithTag("build-error-output").assertIsDisplayed()
        onNodeWithTag("build-error-output-panel").assertIsDisplayed()
        onNodeWithTag("build-error-output-scroll").assertIsDisplayed()
        onNodeWithText("LLM prompt").assertIsDisplayed()
        onNodeWithText("Build output").assertIsDisplayed()
        onNodeWithTag("copy-build-error-button").assertIsDisplayed()
        onNodeWithTag("copy-build-error-prompt-button").assertIsDisplayed()
        onNodeWithTag("cancel-reproduction-button").assertIsDisplayed()
        onNodeWithText("Copy prompt").assertIsDisplayed()
        onNodeWithText("Cancel reproduction").performClick()
        assertTrue(canceled)
    }

    private fun loaded(
        name: String,
        description: String,
        clientChanges: String = "",
        serverChanges: String = "",
        serverUntrackedChanges: String = "",
    ): LoadedBugReport {
        val path = createTempDirectory("bug-reproducer-ui").resolve("$name.json").also { it.writeText("{}") }
        return LoadedBugReport(
            path,
            StoredClientBugReport(
                ClientBugReport(
                    description,
                    createdAt = Clock.System.now(),
                    diagnostics = ClientBugDiagnostics(
                        values = mapOf("client.git.localChanges" to clientChanges),
                    ),
                ),
                ServerBugDiagnostics(
                    values = mapOf(
                        "server.changes" to serverChanges,
                        "server.untrackedChanges" to serverUntrackedChanges,
                    ),
                    failures = emptyList(),
                ),
                ServerState(),
                Clock.System.now(),
            ),
        )
    }
}
