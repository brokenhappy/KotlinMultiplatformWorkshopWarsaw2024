package bugreproducer

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kmpworkshop.client.ClientEntryPoint
import kmpworkshop.client.ClientSettings
import kmpworkshop.client.defaultClientMetadata
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image.Companion.makeFromEncoded
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

private val LauncherBackground = Color(0xFFF5F7FB)
private val MutedText = Color(0xFF667085)
private val WarningText = Color(0xFF9A6700)
private val ErrorText = Color(0xFFB42318)

fun main(args: Array<String>) {
    val configArgument = args.firstOrNull { it.startsWith("--reproducer-config=") }
    if (configArgument != null) {
        println(ReproducerReadyMarker)
        runReproducer(Path.of(configArgument.substringAfter('=')))
        return
    }

    val bugDirectory = bugDirectoryFromEnvironment()
    val reports = loadClientBugReports(bugDirectory)
    application {
        Window(
            title = "Bug Reproducer",
            onCloseRequest = ::exitApplication,
        ) {
            LauncherApp(reports, onExit = ::exitApplication)
        }
    }
}

@Composable
fun LauncherApp(
    loadResult: BugReportLoadResult,
    onExit: () -> Unit,
    onOpenCodexSession: (Path) -> Unit = ::openCodexSession,
) {
    var selectedIndex by remember { mutableStateOf(if (loadResult.reports.isEmpty()) -1 else 0) }
    var status by remember { mutableStateOf<LauncherStatus>(LauncherStatus.Idle) }
    var requestedReport by remember { mutableStateOf<LoadedBugReport?>(null) }
    var codexLaunchError by remember { mutableStateOf<String?>(null) }

    fun openCodexSession(worktree: Path) {
        codexLaunchError = runCatching { onOpenCodexSession(worktree) }
            .exceptionOrNull()
            ?.let { "Could not open a Codex session in $worktree: ${it.message ?: it::class.simpleName}" }
    }

    fun cancelReproduction() {
        requestedReport = null
        status = LauncherStatus.Idle
    }

    LaunchedEffect(requestedReport) {
        val report = requestedReport ?: return@LaunchedEffect
        val ui = object : ReproducerUi {
            override suspend fun showConflict(worktree: Path, prompt: String) {
                val resolved = CompletableDeferred<Unit>()
                val conflict = LauncherStatus.Conflicts(worktree, prompt, resolved)
                withContext(Dispatchers.Main.immediate) {
                    status = conflict
                }
                try {
                    resolved.await()
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (status === conflict) status = LauncherStatus.Idle
                    }
                }
            }

            override suspend fun showCompilationFailure(worktree: Path, prompt: String, output: String) {
                val retry = CompletableDeferred<Unit>()
                val failure = LauncherStatus.CompilationFailed(worktree, prompt, output, retry)
                withContext(Dispatchers.Main.immediate) {
                    status = failure
                }
                try {
                    retry.await()
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (status === failure) status = LauncherStatus.Idle
                    }
                }
            }

            override suspend fun showRunning(worktree: Path, warnings: List<String>): Nothing {
                val running = LauncherStatus.Running(worktree, warnings)
                withContext(Dispatchers.Main.immediate) {
                    status = running
                }
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (status === running) status = LauncherStatus.Idle
                    }
                }
            }
        }
        try {
            runReproducer(report, ui)
            status = LauncherStatus.Idle
            requestedReport = null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            status = LauncherStatus.Failed(failure.message ?: failure::class.simpleName.orEmpty())
            requestedReport = null
        } finally {
            if (requestedReport === report) {
                requestedReport = null
                if (status !is LauncherStatus.Failed) status = LauncherStatus.Idle
            }
        }
    }

    val selected = loadResult.reports.getOrNull(selectedIndex)
    Surface(Modifier.fillMaxSize().background(LauncherBackground)) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Client bug reports", style = MaterialTheme.typography.h4)
                    Text(
                        "Select a persisted report to inspect its evidence and reproduce it in an isolated worktree.",
                        color = MutedText,
                    )
                }
                OutlinedButton(onClick = onExit, modifier = Modifier.testTag("revert-and-quit-button")) {
                    Text("Revert and Quit")
                }
            }
            if (loadResult.malformed.isNotEmpty()) {
                Text(
                    "${loadResult.malformed.size} report file(s) could not be loaded: " +
                        loadResult.malformed.joinToString { it.path.fileName.toString() },
                    color = WarningText,
                    modifier = Modifier.testTag("malformed-reports-warning"),
                )
            }
            Row(Modifier.fillMaxSize().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.width(310.dp).fillMaxHeight()) {
                    if (loadResult.reports.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No client reports found.", color = MutedText)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize().testTag("report-list")) {
                            itemsIndexed(loadResult.reports, key = { _, item -> item.path.toString() }) { index, item ->
                                ReportListItem(
                                    item = item,
                                    selected = index == selectedIndex,
                                    onClick = { selectedIndex = index },
                                )
                            }
                        }
                    }
                }
                Card(Modifier.weight(1f).fillMaxHeight()) {
                    if (selected == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select a report.", color = MutedText)
                        }
                    } else {
                        ReportDetails(
                            item = selected,
                            status = status,
                            reproductionActive = requestedReport != null,
                            onPrepare = {
                                status = LauncherStatus.Idle
                                requestedReport = selected
                            },
                        )
                    }
                }
            }
            when (val current = status) {
                LauncherStatus.Idle -> if (requestedReport != null) {
                    Text("Preparing an isolated worktree…", color = MutedText)
                }
                is LauncherStatus.Running -> {
                    RunningReproductionStatus(
                        worktree = current.worktree,
                        warnings = current.warnings,
                        onCancel = ::cancelReproduction,
                    )
                }
                is LauncherStatus.Failed -> SelectableLaunchError(current.message)
                is LauncherStatus.CompilationFailed -> {
                    CompilationFailureStatus(
                        worktree = current.worktree,
                        prompt = current.prompt,
                        output = current.output,
                        retry = current.retry,
                        onOpenCodexSession = ::openCodexSession,
                        onCancel = ::cancelReproduction,
                    )
                }
                is LauncherStatus.Conflicts -> {
                    ConflictReproductionStatus(
                        worktree = current.worktree,
                        prompt = current.prompt,
                        onOpenCodexSession = ::openCodexSession,
                        onResolved = { current.resolved.complete(Unit) },
                        onCancel = ::cancelReproduction,
                    )
                }
            }
            codexLaunchError?.let { error ->
                SelectableLaunchError(error)
            }
        }
    }
}

@Composable
internal fun RunningReproductionStatus(
    worktree: Path,
    warnings: List<String>,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer(Modifier.testTag("started-status-selection")) {
            Column {
                Text("Reproducer started from $worktree.", color = Color(0xFF26734D))
                warnings.forEach { Text(it, color = WarningText) }
            }
        }
        CancelReproductionButton(onCancel)
    }
}

@Composable
internal fun ConflictReproductionStatus(
    worktree: Path,
    prompt: String,
    onOpenCodexSession: (Path) -> Unit,
    onResolved: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Resolve the conflicts shown below before starting Gradle.", color = WarningText)
        Text(prompt, color = MutedText, modifier = Modifier.testTag("conflict-prompt"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CopyPromptButton(prompt, "copy-conflict-prompt-button")
            OpenCodexSessionButton(worktree, onOpenCodexSession)
            CancelReproductionButton(onCancel)
            Button(
                onClick = onResolved,
                modifier = Modifier.testTag("conflicts-resolved-button"),
            ) { Text("Conflicts resolved! Start reproducer!") }
        }
    }
}

@Composable
internal fun SelectableLaunchError(message: String, maxHeight: Dp = 220.dp) {
    Column(
        modifier = Modifier.testTag("launch-error-selection"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SelectionContainer {
            Box(
                Modifier.fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .testTag("launch-error-scroll"),
            ) {
                Text(message, color = ErrorText, modifier = Modifier.testTag("launch-error"))
            }
        }
        OutlinedButton(
            onClick = { copyToSystemClipboard(message) },
            modifier = Modifier.testTag("copy-launch-error-button"),
        ) {
            Text("Copy error")
        }
    }
}

private fun copyToSystemClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

private sealed interface LauncherStatus {
    data object Idle : LauncherStatus
    data class Running(val worktree: Path, val warnings: List<String>) : LauncherStatus
    data class Conflicts(val worktree: Path, val prompt: String, val resolved: CompletableDeferred<Unit>) : LauncherStatus
    data class CompilationFailed(
        val worktree: Path,
        val prompt: String,
        val output: String,
        val retry: CompletableDeferred<Unit>,
    ) : LauncherStatus
    data class Failed(val message: String) : LauncherStatus
}

@Composable
internal fun CompilationFailureStatus(
    worktree: Path,
    prompt: String,
    output: String,
    retry: CompletableDeferred<Unit>,
    onOpenCodexSession: (Path) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FailureTextPanel(
                title = "LLM prompt",
                text = prompt,
                textColor = MutedText,
                panelTag = "build-error-prompt-panel",
                textTag = "build-error-prompt",
                scrollTag = "build-error-prompt-scroll",
            )
            FailureTextPanel(
                title = "Build output",
                text = output,
                textColor = ErrorText,
                panelTag = "build-error-output-panel",
                textTag = "build-error-output",
                scrollTag = "build-error-output-scroll",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { copyToSystemClipboard(output) },
                modifier = Modifier.testTag("copy-build-error-button"),
            ) {
                Text("Copy error")
            }
            CopyPromptButton(prompt, "copy-build-error-prompt-button")
            OpenCodexSessionButton(worktree, onOpenCodexSession)
            CancelReproductionButton(onCancel)
            OutlinedButton(
                onClick = { retry.complete(Unit) },
                modifier = Modifier.testTag("retry-reproduction-button"),
            ) { Text("Retry reproduction") }
        }
    }
}

@Composable
private fun CancelReproductionButton(onCancel: () -> Unit) {
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.testTag("cancel-reproduction-button"),
    ) {
        Text("Cancel reproduction")
    }
}

@Composable
private fun RowScope.FailureTextPanel(
    title: String,
    text: String,
    textColor: Color,
    panelTag: String,
    textTag: String,
    scrollTag: String,
) {
    Surface(
        modifier = Modifier.weight(1f).fillMaxHeight().testTag(panelTag),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, Color(0xFFD0D5DD)),
        color = Color.White,
        elevation = 1.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                title,
                style = MaterialTheme.typography.subtitle2,
                color = Color(0xFF344054),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Divider(color = Color(0xFFE4E7EC))
            SelectionContainer {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag(scrollTag),
                ) {
                    Text(text, color = textColor, modifier = Modifier.testTag(textTag))
                }
            }
        }
    }
}

@Composable
private fun CopyPromptButton(prompt: String, testTag: String) {
    OutlinedButton(
        onClick = { copyToSystemClipboard(prompt) },
        modifier = Modifier.testTag(testTag),
    ) { Text("Copy prompt") }
}

internal const val OpenCodexSessionButtonLabel = "Open Codex session"

@Composable
internal fun OpenCodexSessionButton(worktree: Path, onOpenCodexSession: (Path) -> Unit) {
    OutlinedButton(
        onClick = { onOpenCodexSession(worktree) },
        modifier = Modifier.testTag("open-codex-session-button"),
    ) {
        Text(OpenCodexSessionButtonLabel)
    }
}

@Composable
private fun ReportListItem(item: LoadedBugReport, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFFE7E5FF) else Color.Transparent)
            .clickable(onClick = onClick)
            .testTag("report-row-${item.path.fileName}"),
    ) {
        Text(item.report.clientReport.description.lineSequence().firstOrNull().orEmpty().ifBlank { "(No description)" }, Modifier.padding(14.dp, 12.dp, 14.dp, 2.dp))
        Text(item.report.receivedAt.toString(), color = MutedText, modifier = Modifier.padding(14.dp, 0.dp, 14.dp, 12.dp))
        Divider()
    }
}

@Composable
private fun ReportDetails(
    item: LoadedBugReport,
    status: LauncherStatus,
    reproductionActive: Boolean,
    onPrepare: () -> Unit,
) {
    var selectedImage by remember(item.path) { mutableStateOf<DecodedBugImage?>(null) }
    val report = item.report
    val clientDiagnostics = report.clientReport.diagnostics
    val serverDiagnostics = report.serverDiagnostics
    val clientChanges = clientDiagnostics.values["client.git.localChanges"].orEmpty()
    val serverChanges = combineCapturedChanges(
        serverDiagnostics.values["server.changes"].orEmpty(),
        serverDiagnostics.values["server.untrackedChanges"].orEmpty(),
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Report details", style = MaterialTheme.typography.h5, modifier = Modifier.weight(1f))
            Button(
                onClick = onPrepare,
                enabled = !reproductionActive && (status == LauncherStatus.Idle || status is LauncherStatus.Failed),
                modifier = Modifier.testTag("prepare-reproduction-button"),
            ) { Text("Prepare reproduction") }
        }
        Text("Received ${report.receivedAt}", color = MutedText)
        Divider()
        Text("Description", style = MaterialTheme.typography.h6)
        Text(report.clientReport.description, modifier = Modifier.testTag("report-description"))
        Text("Images", style = MaterialTheme.typography.h6)
        if (report.clientReport.attachments.isEmpty()) Text("No images attached.", color = MutedText)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            report.clientReport.attachments.forEach { attachment ->
                val decoded = remember(attachment.dataBase64) { decodeBugImage(attachment).getOrNull() }
                if (decoded == null) {
                    Text("${attachment.fileName}: invalid image", color = ErrorText)
                } else {
                    val bitmap = remember(decoded.bytes.contentHashCode()) { makeFromEncoded(decoded.bytes).toComposeImageBitmap() }
                    Column(Modifier.width(130.dp).clickable { selectedImage = decoded }.testTag("image-${attachment.fileName}")) {
                        Image(bitmap, attachment.fileName, Modifier.size(120.dp))
                        Text(attachment.fileName, color = MutedText)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Client diagnostics", style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
            CopyChangesButton(
                label = "Copy client changes",
                changes = clientChanges,
                testTag = "copy-client-changes-button",
            )
        }
        DiagnosticMap(clientDiagnostics.values)
        DiagnosticFailures(clientDiagnostics.failures)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Server diagnostics", style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
            CopyChangesButton(
                label = "Copy server changes",
                changes = serverChanges,
                testTag = "copy-server-changes-button",
            )
        }
        DiagnosticMap(serverDiagnostics.values)
        DiagnosticFailures(serverDiagnostics.failures)
        Text("Captured server state", style = MaterialTheme.typography.h6)
        Text(
            Json { prettyPrint = true }.encodeToString(report.serverState),
            modifier = Modifier.fillMaxWidth().background(Color(0xFFF8F9FA)).padding(10.dp).testTag("server-state"),
        )
    }

    selectedImage?.let { decoded ->
        Dialog(onCloseRequest = { selectedImage = null }, title = "Bug report image") {
            Surface(Modifier.padding(16.dp)) {
                Image(makeFromEncoded(decoded.bytes).toComposeImageBitmap(), "Decoded bug report image")
            }
        }
    }
}

@Composable
private fun DiagnosticMap(values: Map<String, String>) {
    if (values.isEmpty()) Text("No diagnostic values.", color = MutedText)
    values.toSortedMap().forEach { (name, value) ->
        Column(Modifier.fillMaxWidth().testTag("diagnostic-$name")) {
            Text(name, color = MutedText)
            Text(value)
        }
    }
}

@Composable
private fun DiagnosticFailures(failures: List<String>) {
    failures.forEach { Text("$it", color = WarningText) }
}

private fun combineCapturedChanges(trackedChanges: String, untrackedChanges: String): String = buildString {
    append(trackedChanges)
    if (trackedChanges.isNotEmpty() && untrackedChanges.isNotEmpty() && !trackedChanges.endsWith('\n')) {
        append('\n')
    }
    append(untrackedChanges)
}

@Composable
private fun CopyChangesButton(label: String, changes: String, testTag: String) {
    OutlinedButton(
        enabled = changes.isNotEmpty(),
        onClick = { copyToSystemClipboard(changes) },
        modifier = Modifier.testTag(testTag),
    ) {
        Text(label)
    }
}

private fun runReproducer(configPath: Path) {
    val config = readReproductionConfig(configPath)
    application {
        Window(
            title = "Bug Reproducer — historical client",
            onCloseRequest = ::exitApplication,
        ) {
            ReproducerApp(config, onExit = ::exitApplication)
        }
    }
}

@Composable
private fun ReproducerApp(config: ReproductionConfig, onExit: () -> Unit) {
    val runtimeResource = remember(config) {
        embeddedReproducerRuntime(config.report.serverState, config.apiKey)
    }
    var runtime by remember(config) { mutableStateOf<EmbeddedReproducerRuntime?>(null) }
    val settings = remember(config) { kotlinx.coroutines.flow.MutableStateFlow(ClientSettings(config.settings.zoom)) }
    LaunchedEffect(runtimeResource) {
        try {
            runtimeResource.use {
                runtime = it
                awaitCancellation()
            }
        } finally {
            runtime = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF182230)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Historical client · ${config.report.receivedAt}", color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = onExit, modifier = Modifier.testTag("reproducer-revert-and-quit-button")) {
                Text("Revert and Quit", color = Color.White)
            }
        }
        runtime?.let { activeRuntime ->
            MaterialTheme {
                ClientEntryPoint(
                    server = activeRuntime.client,
                    submitBugReport = activeRuntime::submit,
                    clientMetadata = defaultClientMetadata,
                    clientSettings = settings,
                    onClientSettingsChange = { settings.value = it },
                    openReportBug = false,
                    onReportBugClosed = {},
                )
            }
        }
    }
}
