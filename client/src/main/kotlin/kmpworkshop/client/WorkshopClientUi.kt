@file:OptIn(
    DelicateHotReloadApi::class,
    ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class,
)

package kmpworkshop.client

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import kmpworkshop.common.*
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage.FindMinimumAgeOfUserTask
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage.FindOldestUserTask
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage.PalindromeCheckTask
import kmpworkshop.solutions.*
import com.woutwerkman.calltreevisualizer.coroutineintegration.CallStackTrackEvent
import com.woutwerkman.calltreevisualizer.coroutineintegration.trackingCallStacks
import com.woutwerkman.calltreevisualizer.globalScopeContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import org.jetbrains.annotations.TestOnly
import org.jetbrains.compose.reload.AfterHotReloadEffect
import org.jetbrains.compose.reload.DelicateHotReloadApi
import java.awt.Desktop
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

public val workshopSolutions: CoroutinePuzzleWorkshopSolutions = CoroutinePuzzleWorkshopSolutions(
    sumSolution = { numberSummer(it) },
    collectSolution = { showingHowItsFlowing(it) },
    shipmentTrackingSolution = { shareShipmentTracking(it) },
    maximumAgeFindingTheSecondCoroutineSolution = { maximumAgeFindingWithCoroutines(it) },
    mappingLegacyApiCoroutineSolution = { mapFromLegacyApi(it) },
    exceptionHandlingSolution = { exceptionHandlingPuzzle(it) },
    fileExposureSolution = { allowPeopleToDownloadExposedFile(it) },
)

public val kotlinBasicsPuzzleSolutions: KotlinBasicsPuzzleSolutions = KotlinBasicsPuzzleSolutions(
    palindromeCheckSolution = ::doPalindromeCheckOn,
    minimumAgeSolution = ::serializableFindMinimumAgeOf,
    oldestUserSolution = ::serializableFindOldestUserAmong,
)

@Composable
fun WorkshopClient(
    server: WorkshopServer,
    solutions: CoroutinePuzzleWorkshopSolutions = workshopSolutions,
    kotlinBasicsSolutions: KotlinBasicsPuzzleSolutions = kotlinBasicsPuzzleSolutions,
    clientMetadata: ClientMetadata,
    clientSettings: Flow<ClientSettings> = flowOf(ClientSettings()),
    onClientSettingsChange: (ClientSettings) -> Unit = {},
    submitBugReport: suspend (ClientBugReport) -> ClientBugReportSubmissionResult = {
        ClientBugReportSubmissionResult.Rejected("Bug reporting is not connected.")
    },
    openReportBug: Boolean = false,
    onReportBugClosed: () -> Unit = {},
) {
    context(clientMetadata) {
        WorkshopClientContent(
            server,
            solutions,
            kotlinBasicsSolutions,
            clientSettings,
            onClientSettingsChange,
            submitBugReport,
            openReportBug,
            onReportBugClosed,
        )
    }
}

@Composable
context(clientMetadata: ClientMetadata)
private fun WorkshopClientContent(
    server: WorkshopServer,
    solutions: CoroutinePuzzleWorkshopSolutions,
    kotlinBasicsSolutions: KotlinBasicsPuzzleSolutions,
    clientSettings: Flow<ClientSettings>,
    onClientSettingsChange: (ClientSettings) -> Unit,
    submitBugReport: suspend (ClientBugReport) -> ClientBugReportSubmissionResult,
    openReportBug: Boolean,
    onReportBugClosed: () -> Unit,
) {
    val settings by clientSettings.collectAsState(initial = ClientSettings())
    var settingsIsOpen by remember { mutableStateOf(false) }
    var reportIsOpen by remember { mutableStateOf(false) }
    val reportDraftStore = remember { ClientBugDraftStore() }
    var reportDraft by remember { mutableStateOf(reportDraftStore.load()) }
    val focusRequester = remember { FocusRequester() }
    val baseDensity = LocalDensity.current

    LaunchedEffect(settingsIsOpen) {
        if (!settingsIsOpen) {
            // Done disposes the focused settings button; wait for that disposal before reclaiming focus.
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }

    fun ClientSettings.applyZoom(change: (Float) -> Float): ClientSettings =
        copy(zoom = change(zoom).coerceIn(MinClientZoom, MaxClientZoom))

    val zoomShortcutModifier = remember { clientShortcutModifier() }
    val stage by remember(server) { server.currentStage() }.collectAsState(initial = WorkshopStage.Registration)
    var hotReloadVersion by remember { mutableStateOf(0L) }
    var lastRunHotReloadVersion by remember(stage) { mutableStateOf<Long?>(null) }
    val hasHotReloadSinceLastRun = lastRunHotReloadVersion != hotReloadVersion
    val history = remember(stage) { mutableStateListOf<CoroutinePuzzleHistoryBatch>() }
    var result by remember(stage) { mutableStateOf<CoroutinePuzzleSolutionResult?>(null) }
    var kotlinBasicsResult by remember(stage) { mutableStateOf<KotlinBasicsPuzzleResult?>(null) }
    var status by remember(stage) { mutableStateOf<String?>(null) }
    var openError by remember(stage) { mutableStateOf<String?>(null) }
    var attemptVersion by remember(stage) { mutableStateOf(0L) }
    var activeRun by remember { mutableStateOf<Job?>(null) }
    var debuggerEvents by remember(stage) { mutableStateOf<Channel<CallStackTrackEvent>?>(null) }
    var debuggerBatchBoundaries by remember(stage) { mutableStateOf<Channel<Unit>?>(null) }
    var debuggerBatchController by remember(stage) { mutableStateOf<CoroutineDebuggerBatchController?>(null) }
    val scope = rememberCoroutineScope()
    val latestActiveRun = rememberUpdatedState(activeRun)
    val latestDebuggerEvents = rememberUpdatedState(debuggerEvents)

    LaunchedEffect(reportDraft) {
        withContext(Dispatchers.IO) { reportDraftStore.save(reportDraft) }
    }
    LaunchedEffect(openReportBug) {
        if (openReportBug) reportIsOpen = true
    }

    AfterHotReloadEffect {
        latestActiveRun.value?.cancel()
        activeRun = null
        latestDebuggerEvents.value?.close()
        debuggerEvents = null
        debuggerBatchBoundaries?.close()
        debuggerBatchBoundaries = null
        debuggerBatchController = null
        hotReloadVersion++
    }

    LaunchedEffect(stage, hotReloadVersion) {
        attemptVersion++
        history.clear()
        result = null
        kotlinBasicsResult = null
        status = null
    }

    DisposableEffect(stage) {
        onDispose {
            latestActiveRun.value?.cancel()
            activeRun = null
            latestDebuggerEvents.value?.close()
            debuggerEvents = null
            debuggerBatchBoundaries?.close()
            debuggerBatchBoundaries = null
            debuggerBatchController = null
        }
    }

    fun recordRun() {
        lastRunHotReloadVersion = hotReloadVersion
    }

    fun startKotlinBasicsRun(stage: WorkshopStage.KotlinBasicsPuzzleStage) {
        recordRun()
        kotlinBasicsResult = null
        status = "Running test…"
        val run = scope.launch(start = CoroutineStart.LAZY) {
            try {
                kotlinBasicsResult = runKotlinBasicsPuzzle(server, stage, kotlinBasicsSolutions)
                status = when (val puzzleResult = kotlinBasicsResult) {
                    KotlinBasicsPuzzleResult.Success -> "Test finished."
                    is KotlinBasicsPuzzleResult.Failed -> "Test failed for input ${puzzleResult.input}: got ${puzzleResult.actual}, expected ${puzzleResult.expected}."
                    is KotlinBasicsPuzzleResult.CustomFailure -> "Test failed: ${puzzleResult.message}"
                    null -> "Test finished."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                status = "Test failed: ${failure.message ?: failure::class.simpleName}"
            } finally {
                if (activeRun === coroutineContext[Job]) activeRun = null
            }
        }
        activeRun = run
        run.start()
    }

    Surface(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                if (event.key == Key.Escape && settingsIsOpen) {
                    settingsIsOpen = false
                    return@onPreviewKeyEvent true
                }
                if (event.key == Key.Escape && reportIsOpen) {
                    reportIsOpen = false
                    onReportBugClosed()
                    return@onPreviewKeyEvent true
                }
                if (!event.isCtrlPressed && !event.isMetaPressed) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Comma -> {
                        settingsIsOpen = true
                        true
                    }
                    event.key == Key.Plus || (event.key == Key.Equals && event.isShiftPressed) -> {
                        onClientSettingsChange(settings.applyZoom { it + ClientZoomStep })
                        true
                    }
                    event.key == Key.Minus -> {
                        onClientSettingsChange(settings.applyZoom { it - ClientZoomStep })
                        true
                    }
                    event.key == Key.Zero -> {
                        onClientSettingsChange(settings.applyZoom { DefaultClientZoom })
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density * settings.zoom,
                    fontScale = baseDensity.fontScale * settings.zoom,
                ),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = { settingsIsOpen = true }) { Text("Settings") }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (val stage = stage) {
                            WorkshopStage.Registration -> RegistrationWaiting()
                            is WorkshopStage.KotlinBasicsPuzzleStage -> StagePage(
                                stage,
                                openError,
                                { openError = openStageFile(stage) },
                            ) {
                                PuzzleRunButton(
                                    hasHotReloadSinceLastRun = hasHotReloadSinceLastRun,
                                    activeRun = activeRun,
                                    onClick = { startKotlinBasicsRun(stage) },
                                )
                                Text(
                                    status ?: "Preparing test…",
                                    modifier = Modifier.testTag("puzzle-status"),
                                    color = kotlinBasicsResultColor(kotlinBasicsResult),
                                )
                            }
                            is WorkshopStage.CoroutinePuzzleStage -> StagePage(
                                stage,
                                openError,
                                { openError = openStageFile(stage) },
                            ) {
                                fun startRun(stepped: Boolean) {
                                    recordRun()
                                    history.clear()
                                    result = null
                                    debuggerEvents?.close()
                                    debuggerEvents = null
                                    debuggerBatchBoundaries?.close()
                                    debuggerBatchBoundaries = null
                                    attemptVersion++
                                    status = "Running test…"
                                    val trackedEvents =
                                        if (stepped) Channel<CallStackTrackEvent>(Channel.RENDEZVOUS) else null
                                    val batchBoundaries = if (stepped) Channel<Unit>(Channel.CONFLATED) else null
                                    val batchController = CoroutineDebuggerBatchController()
                                    debuggerEvents = trackedEvents
                                    debuggerBatchBoundaries = batchBoundaries
                                    debuggerBatchController = batchController
                                    val run = scope.launch(start = CoroutineStart.LAZY) {
                                        try {
                                            server.coroutinePuzzle(stage).solveAsFlow {
                                                val userSolution = solutions.asSolution(stage)
                                                if (trackedEvents == null) {
                                                    withContext(NoOpStackTracker) {
                                                        withImportantCleanupAndOverriddenGlobalScope {
                                                            userSolution()
                                                        }
                                                    }
                                                } else {
                                                    val debuggerQuiescence =
                                                        AutoBatchedFunctionId<Unit, Unit> { batch ->
                                                            check(batch.isEmpty()) { "Debugger quiescence must never receive batched calls" }
                                                            batchController.onEmptyBatch(requireNotNull(batchBoundaries))
                                                        }
                                                    debuggerQuiescence.autoBatchedOnQuiescence {
                                                        trackingCallStacks(
                                                            block = {
                                                                withImportantCleanupAndOverriddenGlobalScope {
                                                                    userSolution()
                                                                }
                                                            },
                                                            emit = { event ->
                                                                assumeNotQuiescent {
                                                                    trackedEvents.send(event)
                                                                }
                                                            },
                                                        )
                                                    }
                                                }
                                            }.collect { state ->
                                                when (state) {
                                                    is CoroutinePuzzleSolveState.Running -> {
                                                        history.add(state.batch)
                                                        status = "Running test…"
                                                    }
                                                    is CoroutinePuzzleSolveState.Completed -> {
                                                        result = state.result
                                                        status = state.result.toMessage()
                                                    }
                                                }
                                            }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Throwable) {
                                            status = "Test failed: ${failure.message ?: failure::class.simpleName}"
                                        } finally {
                                            trackedEvents?.close()
                                            if (activeRun === coroutineContext[Job]) activeRun = null
                                        }
                                    }
                                    activeRun = run
                                    run.start()
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PuzzleRunButton(
                                        hasHotReloadSinceLastRun = hasHotReloadSinceLastRun,
                                        activeRun = activeRun,
                                        onClick = { startRun(stepped = false) },
                                    )
                                    PuzzleRunButton(
                                        hasHotReloadSinceLastRun = hasHotReloadSinceLastRun,
                                        activeRun = activeRun,
                                        testTag = "puzzle-run-stepped-button",
                                        text = "Run Stepped",
                                        onClick = { startRun(stepped = true) },
                                    )
                                }

                                status?.let {
                                    Text(
                                        it,
                                        modifier = Modifier.testTag("puzzle-status"),
                                        color = resultColor(result),
                                    )
                                }
                                Column(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    debuggerEvents?.let { events ->
                                        CoroutineDebuggerPanel(
                                            events = events,
                                            batchBoundaries = debuggerBatchBoundaries,
                                            batchController = requireNotNull(debuggerBatchController),
                                            enabled = activeRun != null,
                                        )
                                    }
                                    CoroutineTimelineWithMetadata(
                                        history = history,
                                        result = result,
                                        attemptVersion = attemptVersion,
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (settingsIsOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.24f))
                        .padding(24.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(12.dp),
                        elevation = 8.dp,
                        color = MaterialTheme.colors.surface.copy(alpha = 0.96f),
                    ) {
                        ClientSettingsPage(
                            settings = settings,
                            shortcutModifier = zoomShortcutModifier,
                            onSettingsChange = onClientSettingsChange,
                            onDismiss = { settingsIsOpen = false },
                        )
                    }
                }
            }

            if (reportIsOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.24f))
                        .padding(24.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(12.dp),
                        elevation = 8.dp,
                        color = MaterialTheme.colors.surface.copy(alpha = 0.96f),
                    ) {
                        ClientBugReportPage(
                            draft = reportDraft,
                            settings = settings,
                            onDraftChange = { reportDraft = it },
                            onSubmit = submitBugReport,
                            onDismiss = {
                                reportIsOpen = false
                                onReportBugClosed()
                            },
                            onReset = {
                                reportDraftStore.clear()
                                reportDraft = ClientBugReportDraft()
                            },
                        )
                    }
                }
            }
        }
    }
}

private suspend fun <T> withImportantCleanupAndOverriddenGlobalScope(block: suspend CoroutineScope.() -> T): T =
    withImportantCleanup {
        globalScopeContext = currentCoroutineContext() + SupervisorJob(currentCoroutineContext().job)
        try {
            coroutineScope { block() }
        } finally {
            globalScopeContext = EmptyCoroutineContext
        }
    }

@Composable
private fun RegistrationWaiting() = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text("The workshop is in registration. Waiting for the host to open a puzzle…")
}

@Composable
private fun ClientSettingsPage(
    settings: ClientSettings,
    shortcutModifier: String,
    onSettingsChange: (ClientSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember(clientApiKey) { mutableStateOf(false) }
    var apiKeyIsVisible by remember(clientApiKey) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.h4, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss) { Text("Done") }
        }
        Divider(color = Color(0xFFE4E7EB))

        Text("Display", style = MaterialTheme.typography.h6, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Zoom", fontWeight = FontWeight.SemiBold)
                Text("Adjust the size of the workshop client UI.", color = Color(0xFF5F6368))
            }
            Text("${(settings.zoom * 100).toInt()}%", modifier = Modifier.padding(start = 16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onSettingsChange(settings.copy(zoom = DefaultClientZoom)) },
                enabled = settings.zoom != DefaultClientZoom,
            ) { Text("Reset zoom") }
            Text(
                "$shortcutModifier + / - to zoom, $shortcutModifier + 0 to reset",
                modifier = Modifier.align(Alignment.CenterVertically),
                color = Color(0xFF5F6368),
            )
        }

        Divider(color = Color(0xFFE4E7EB))
        Text("API key", style = MaterialTheme.typography.h6, fontWeight = FontWeight.SemiBold)
        if (clientApiKey == null) {
            Text("No API key is available for this client.", color = Color(0xFF5F6368))
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (apiKeyIsVisible) clientApiKey!! else "••••••••",
                    modifier = Modifier.weight(1f),
                    color = Color(0xFF5F6368),
                )
                TextButton(onClick = { apiKeyIsVisible = !apiKeyIsVisible }) {
                    Text(if (apiKeyIsVisible) "Hide" else "Show")
                }
            }
        }
        OutlinedButton(
            enabled = clientApiKey != null,
            onClick = {
                clientApiKey?.let {
                    clipboard.setText(AnnotatedString(it))
                    copied = true
                }
            },
            modifier = Modifier.testTag("copy-api-key-button"),
        ) { Text("Copy API key") }
        if (copied) {
            Text("API key copied.", color = Color(0xFF2E7D32))
        }
    }
}

private fun clientShortcutModifier(): String =
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) "⌘" else "Ctrl"

@Composable
private fun PuzzleRunButton(
    hasHotReloadSinceLastRun: Boolean,
    activeRun: Job?,
    modifier: Modifier = Modifier,
    testTag: String = "puzzle-run-button",
    text: String = "Run Test",
    onClick: () -> Unit,
) {
    InstantTooltip(
        title = if (hasHotReloadSinceLastRun) {
            "A hot reload has happened since the last run."
        } else {
            "No hot reload has happened since the last run."
        },
        modifier = modifier,
    ) {
        Button(
            modifier = Modifier.testTag(testTag),
            enabled = activeRun == null,
            colors = puzzleRunButtonColors(hasHotReloadSinceLastRun),
            onClick = onClick,
        ) { Text(text) }
    }
}

@Composable
private fun StagePage(stage: WorkshopStage, openError: String?, onOpen: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stage.displayName().humanize(), style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
                Text("Edit the solution, then run an attempt to see exactly how its coroutines behaved.", color = Color(0xFF5F6368))
            }
            OutlinedButton(onClick = onOpen) { Text("Open solution") }
        }
        Divider(color = Color(0xFFE4E7EB))
        openError?.let { Text(it, color = MaterialTheme.colors.error) }
        content()
    }
}

@Composable
internal fun CoroutineTimeline(
    history: List<CoroutinePuzzleHistoryBatch>,
    result: CoroutinePuzzleSolutionResult?,
    attemptVersion: Long = 0L,
    modifier: Modifier = Modifier,
) {
    context(defaultClientMetadata) {
        CoroutineTimelineWithMetadata(history, result, attemptVersion, modifier)
    }
}

@Composable
context(clientMetadata: ClientMetadata)
internal fun CoroutineTimelineWithMetadata(
    history: List<CoroutinePuzzleHistoryBatch>,
    result: CoroutinePuzzleSolutionResult?,
    attemptVersion: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val calls = remember(attemptVersion, history.size) { coroutineTimeline(history) }
    if (history.isEmpty()) {
        Card(modifier = modifier, backgroundColor = Color(0xFFF7F8FA), elevation = 0.dp) {
            Text("The timeline will appear here as soon as your solution makes its first call.", Modifier.padding(20.dp), color = Color(0xFF5F6368))
        }
        return
    }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    LaunchedEffect(history.size, calls.size) {
        withFrameNanos { }
        withFrameNanos { }
        horizontal.scrollTo(horizontal.maxValue)
        vertical.scrollTo(vertical.maxValue)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Call timeline",
            style = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Each column is one exchange with the puzzle server. Hover a symbol for the full event.",
            style = MaterialTheme.typography.caption,
            color = Color(0xFF5F6368),
        )
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Color(0xFFDDE1E6), RoundedCornerShape(10.dp)).background(Color.White)) {
            Column(Modifier.fillMaxSize().padding(end = 12.dp, bottom = 12.dp).verticalScroll(vertical).horizontalScroll(horizontal)) {
                Row(Modifier.background(Color(0xFFF4F6F8))) {
                    TimelineCell("Call", "The function call made by your solution.", width = 250, isHeader = true)
                    repeat(history.size) { TimelineCell("${it + 1}", "Exchange ${it + 1} in this attempt.", width = 48, isHeader = true) }
                }
                calls.forEach { call ->
                    Row {
                        TimelineCell(
                            clientMetadata.descriptionFor(call.endpoint),
                            clientMetadata.descriptionFor(call.endpoint),
                            width = 250,
                        )
                        repeat(history.size) { batch ->
                                val marker = markerAt(call, batch, history.lastIndex)
                                TimelineMarkerCell(
                                    marker = marker,
                                    isUncompletedFailure = result != null && call.events.ifEmpty { listOf(call.asEvent()) }.any { it.endBatch == null },
                                    isStart = batch == call.startBatch,
                                    isEnd = if (call.events.isEmpty()) {
                                        batch == call.endBatch || (call.endBatch == null && batch == history.lastIndex)
                                    } else {
                                        batch == call.events.maxOf { it.endBatch ?: history.lastIndex }
                                    },
                                testTag = "timeline-marker-${call.callId}-$batch",
                            )
                        }
                    }
                    Divider(color = Color(0xFFF0F1F2))
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(vertical), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
        Text("● started   ✓ returned   ! threw   × cancellation requested   ⊘ cancelled   … still running", style = MaterialTheme.typography.caption, color = Color(0xFF5F6368))
        if (result != null && calls.any { it.endBatch == null }) {
            Text("Red rows are calls the puzzle never completed; start there when debugging this attempt.", style = MaterialTheme.typography.caption, color = Color(0xFFB3261E))
        }
    }
}

private data class Marker(val text: String, val title: String? = null, val detail: String? = null)

private fun markerAt(call: CoroutineTimelineCall, batch: Int, lastBatch: Int): Marker {
    if (call.events.isNotEmpty()) {
        val emission = call.events.firstOrNull { it.endBatch == batch }
        if (emission != null) {
            return when (emission.completion) {
                TimelineCompletion.RETURNED -> Marker("✓", "Emission returned", "Result: ${displayValue(emission.returnValue)}")
                TimelineCompletion.THREW -> Marker("!", "Threw", "Exception: ${emission.exceptionMessage}")
                TimelineCompletion.CANCELLED -> Marker("⊘", "Cancellation completed", "The puzzle confirmed that this call was cancelled.")
                null -> Marker("━", "Running")
            }
        }
        val cancellation = call.events.firstOrNull { it.cancellationRequestedBatch == batch }
        if (cancellation != null) return Marker("×", "Cancellation requested", "Your coroutine asked the puzzle to cancel this call.")
        if (batch == call.startBatch) return Marker("●", "Started", "The flow collector started.")
        val lastBatchForFlow = call.events.maxOf { it.endBatch ?: lastBatch }
        if (batch in call.startBatch..lastBatchForFlow) return Marker("━", "Flow collector active")
        return Marker("")
    }
    val event = call.events.firstOrNull { batch == it.endBatch || batch == it.cancellationRequestedBatch || batch == it.startBatch }
        ?: call.events.lastOrNull { batch in it.startBatch..(it.endBatch ?: lastBatch) }
        ?: call.asEvent()
    return when {
    batch == event.endBatch -> when (event.completion) {
        TimelineCompletion.RETURNED -> Marker("✓", "Returned", "Result: ${displayValue(event.returnValue)}")
        TimelineCompletion.THREW -> Marker("!", "Threw", "Exception: ${event.exceptionMessage}")
        TimelineCompletion.CANCELLED -> Marker("⊘", "Cancellation completed", "The puzzle confirmed that this call was cancelled.")
        null -> Marker("")
    }
    batch == event.cancellationRequestedBatch -> Marker("×", "Cancellation requested", "Your coroutine asked the puzzle to cancel this call.")
    batch == event.startBatch -> Marker(
        "●",
        "Started",
        if (call.argument.isUnitValue()) "This call has no argument." else "Argument: ${displayValue(call.argument)}",
    )
    event.endBatch == null && batch == lastBatch -> Marker("…", "Still running", "The puzzle did not complete this call before the attempt ended.")
    batch in event.startBatch..(event.endBatch ?: lastBatch) -> Marker("━", "Running", "This call was in progress during this exchange.")
    else -> Marker("")
    }
}

private fun CoroutineTimelineCall.asEvent() = CoroutineTimelineEvent(callId, startBatch, cancellationRequestedBatch, endBatch, completion, returnValue, exceptionMessage)

private fun displayValue(value: JsonElement?): String = when {
    value == null -> "null"
    value.isUnitValue() -> "Unit"
    else -> value.toString()
}

@Composable
private fun TimelineCell(text: String, tooltip: String, width: Int, isHeader: Boolean = false) {
    InstantTooltip(tooltip, Modifier.width(width.dp).height(40.dp)) { hovered ->
        Box(
            Modifier.fillMaxSize().background(if (hovered) Color(0xFFE8F0FE) else Color.Transparent).padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun TimelineMarkerCell(marker: Marker, isUncompletedFailure: Boolean, isStart: Boolean, isEnd: Boolean, testTag: String) {
    val base = if (isUncompletedFailure) Color(0xFFFFDAD6) else Color(0xFFE8F0FE)
    val shape = RoundedCornerShape(
        topStart = if (isStart) 16.dp else 0.dp,
        bottomStart = if (isStart) 16.dp else 0.dp,
        topEnd = if (isEnd) 16.dp else 0.dp,
        bottomEnd = if (isEnd) 16.dp else 0.dp,
    )
    val content: @Composable (Boolean) -> Unit = { hovered ->
        Box(
            Modifier.width(48.dp).height(40.dp).testTag(testTag).padding(vertical = 5.dp)
                .clip(shape)
                .background(if (hovered) if (isUncompletedFailure) Color(0xFFFFB4AB) else Color(0xFFD2E3FC) else if (marker.text.isEmpty()) Color.Transparent else base)
                .then(if (hovered) Modifier.border(2.dp, Color(0xFF1A73E8), shape) else Modifier)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) { Text(marker.text, fontWeight = FontWeight.Bold, color = if (isUncompletedFailure) Color(0xFF8C1D18) else Color(0xFF174EA6)) }
    }
    if (marker.title == null) content(false) else InstantTooltip(marker.title, detail = marker.detail, content = content)
}

@Composable
private fun InstantTooltip(title: String, modifier: Modifier = Modifier, detail: String? = null, content: @Composable (Boolean) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    TooltipArea(
        delayMillis = 0,
        tooltip = {
            Surface(shape = RoundedCornerShape(8.dp), elevation = 8.dp, color = Color(0xFF202124)) {
                Column(Modifier.widthIn(max = 340.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    detail?.let { Text(it, color = Color(0xFFE8EAED), style = MaterialTheme.typography.caption) }
                }
            }
        },
        modifier = modifier.hoverable(interactionSource),
    ) { content(hovered) }
}

private fun String.humanize(): String = replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")

@Composable
private fun puzzleRunButtonColors(hasHotReloadSinceLastRun: Boolean): ButtonColors = ButtonDefaults.buttonColors(
    backgroundColor = if (hasHotReloadSinceLastRun) {
        MaterialTheme.colors.primary
    } else {
        MaterialTheme.colors.primary.copy(alpha = 0.72f)
    },
)

private fun resultColor(result: CoroutinePuzzleSolutionResult?): Color = when (result) {
    null -> Color.Unspecified
    is CoroutinePuzzleSolutionResult.Success -> Color(0xFF2E7D32)
    else -> Color(0xFFC62828)
}

private fun kotlinBasicsResultColor(result: KotlinBasicsPuzzleResult?): Color = when (result) {
    KotlinBasicsPuzzleResult.Success -> Color(0xFF2E7D32)
    is KotlinBasicsPuzzleResult.Failed, is KotlinBasicsPuzzleResult.CustomFailure -> Color(0xFFC62828)
    null -> Color.Unspecified
}

@TestOnly
public suspend fun runKotlinBasicsPuzzle(
    puzzleProvider: KotlinBasicsPuzzleProvider,
    stage: WorkshopStage.KotlinBasicsPuzzleStage,
    solutions: KotlinBasicsPuzzleSolutions,
): KotlinBasicsPuzzleResult = when (stage) {
    PalindromeCheckTask -> puzzleProvider.kotlinBasicsPuzzle(stage).solve(solutions.palindromeCheckSolution)
    FindMinimumAgeOfUserTask -> puzzleProvider.kotlinBasicsPuzzle(stage).solve(solutions.minimumAgeSolution)
    FindOldestUserTask -> puzzleProvider.kotlinBasicsPuzzle(stage).solve(solutions.oldestUserSolution)
}

private fun WorkshopStage.displayName(): String = when (this) {
    WorkshopStage.Registration -> "Registration"
    is WorkshopStage.KotlinBasicsPuzzleStage -> name
    is WorkshopStage.CoroutinePuzzleStage -> name
}

private fun openStageFile(stage: WorkshopStage): String? {
    val file = File("workshopSolutions/src/main/kotlin/kmpworkshop/solutions", stage.kotlinFile).absoluteFile
    if (!file.isFile) return "Could not find ${file.path}. Open it manually in IntelliJ IDEA."
    val os = System.getProperty("os.name").lowercase()
    val commands = when {
        os.contains("mac") -> listOf(
            // Bundle identifiers keep working when Toolbox adds a version to the application name.
            listOf("open", "-b", "com.jetbrains.intellij", file.path),
            listOf("open", "-b", "com.jetbrains.intellij.ce", file.path),
            listOf("open", "-b", "com.jetbrains.intellij-EAP", file.path),
            listOf("open", "-b", "com.jetbrains.intellij.ce-EAP", file.path),
            listOf("open", "-a", "IntelliJ IDEA", file.path),
            listOf("open", "-a", "IntelliJ IDEA CE", file.path),
            listOf("idea", file.path),
        )
        os.contains("win") -> listOf(listOf("idea64.exe", file.path), listOf("idea.exe", file.path))
        else -> listOf(listOf("idea", file.path), listOf("intellij-idea", file.path))
    }
    if (commands.any { runCatching { ProcessBuilder(it).start().waitFor() == 0 }.getOrDefault(false) }) return null
    if (Desktop.isDesktopSupported() && runCatching { Desktop.getDesktop().open(file); true }.getOrDefault(false)) return null
    return "Could not launch IntelliJ IDEA. Open ${file.path} manually, or configure its desktop file association."
}
