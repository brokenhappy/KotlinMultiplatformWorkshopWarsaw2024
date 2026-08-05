@file:OptIn(
    org.jetbrains.compose.reload.DelicateHotReloadApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package kmpworkshop.client

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import kmpworkshop.common.*
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage.FindMinimumAgeOfUserTask
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage.FindOldestUserTask
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage.PalindromeCheckTask
import kmpworkshop.solutions.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.reload.AfterHotReloadEffect
import org.jetbrains.compose.reload.isHotReloadActive
import java.awt.Desktop
import java.io.File

public val workshopSolutions: CoroutinePuzzleWorkshopSolutions = CoroutinePuzzleWorkshopSolutions(
    sumSolution = { numberSummer(it) },
    collectSolution = { showingHowItsFlowing(it) },
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
) {
    val stage by remember(server) { server.currentStage() }.collectAsState(initial = WorkshopStage.Registration)
    val runGate = remember { WorkshopRunGate(stage) }
    runGate.enterStage(stage)
    var gateVersion by remember { mutableStateOf(0L) }
    val history = remember(stage) { mutableStateListOf<CoroutinePuzzleHistoryBatch>() }
    var result by remember(stage) { mutableStateOf<CoroutinePuzzleSolutionResult?>(null) }
    var kotlinBasicsResult by remember(stage) { mutableStateOf<KotlinBasicsPuzzleResult?>(null) }
    var status by remember(stage) { mutableStateOf<String?>(null) }
    var openError by remember(stage) { mutableStateOf<String?>(null) }
    var activeRun by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    AfterHotReloadEffect {
        runGate.successfulReload()
        gateVersion++
    }

    LaunchedEffect(stage, gateVersion) {
        activeRun?.cancel()
        activeRun = null
        history.clear()
        result = null
        kotlinBasicsResult = null
        status = null
        (stage as? WorkshopStage.KotlinBasicsPuzzleStage)?.let { stage ->
            status = "Running test…"
            status = try {
                kotlinBasicsResult = runKotlinBasicsPuzzle(server, stage, kotlinBasicsSolutions)
                when (val puzzleResult = kotlinBasicsResult) {
                    KotlinBasicsPuzzleResult.Success -> "Test finished. Edit the solution and hot reload to run it again."
                    is KotlinBasicsPuzzleResult.Failed -> "Test failed for input ${puzzleResult.input}: got ${puzzleResult.actual}, expected ${puzzleResult.expected}."
                    is KotlinBasicsPuzzleResult.CustomFailure -> "Test failed: ${puzzleResult.message}"
                    null -> "Test finished."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                "Test failed: ${failure.message ?: failure::class.simpleName}"
            }
        }
    }

    DisposableEffect(Unit) { onDispose { activeRun?.cancel() } }

    Surface(Modifier.fillMaxSize()) {
        when (val stage = stage) {
            WorkshopStage.Registration -> RegistrationWaiting()
            is WorkshopStage.KotlinBasicsPuzzleStage -> StagePage(stage, openError, { openError = openStageFile(stage) }) {
                Text(
                    status ?: "Preparing test…",
                    modifier = Modifier.testTag("puzzle-status"),
                    color = kotlinBasicsResultColor(kotlinBasicsResult),
                )
            }
            is WorkshopStage.CoroutinePuzzleStage -> StagePage(stage, openError, { openError = openStageFile(stage) }) {
                val canRun = runGate.canRun
                Button(
                    modifier = Modifier.testTag("puzzle-run-button"),
                    enabled = canRun,
                    onClick = {
                        check(runGate.startAttempt())
                        status = "Running test…"
                        activeRun = scope.launch {
                            try {
                                runCoroutinePuzzleClientAsFlow(server, stage, solutions).collect {
                                    status = when (it) {
                                        is CoroutinePuzzleSolveState.Running -> {
                                            history.add(it.batch)
                                            "Running test…"
                                        }
                                        is CoroutinePuzzleSolveState.Completed -> {
                                            result = it.result
                                            it.result.toMessage()
                                        }
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                status = "Test failed: ${failure.message ?: failure::class.simpleName}"
                            } finally {
                                activeRun = null
                            }
                        }
                    },
                ) { Text("Run Test") }

                if (!canRun) Text(
                    if (isHotReloadActive) "Waiting for a successful hot reload."
                    else "Run Test is available once. Start the shared WorkshopClient configuration to enable it after edits.",
                    style = MaterialTheme.typography.caption,
                )
                status?.let { Text(it, modifier = Modifier.testTag("puzzle-status"), color = resultColor(result)) }
                CoroutineTimeline(history, result)
            }
        }
    }
}

@Composable
private fun RegistrationWaiting() = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text("The workshop is in registration. Waiting for the host to open a puzzle…")
}

@Composable
private fun StagePage(stage: WorkshopStage, openError: String?, onOpen: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stage.displayName().humanize(), style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
                Text("Edit the solution, then run one attempt to see exactly how its coroutines behaved.", color = Color(0xFF5F6368))
            }
            OutlinedButton(onClick = onOpen) { Text("Open solution") }
        }
        Divider(color = Color(0xFFE4E7EB))
        openError?.let { Text(it, color = MaterialTheme.colors.error) }
        content()
    }
}

@Composable
internal fun CoroutineTimeline(history: List<CoroutinePuzzleHistoryBatch>, result: CoroutinePuzzleSolutionResult?) {
    val calls = remember(history) { coroutineTimeline(history) }
    if (history.isEmpty()) {
        Card(backgroundColor = Color(0xFFF7F8FA), elevation = 0.dp) {
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        TimelineCell(call.endpoint.description, call.endpoint.description, width = 250)
                        repeat(history.size) { batch ->
                            val marker = markerAt(call, batch, history.lastIndex)
                            TimelineMarkerCell(
                                marker = marker,
                                isUncompletedFailure = result != null && call.endBatch == null,
                                isStart = batch == call.startBatch,
                                isEnd = batch == call.endBatch || (call.endBatch == null && batch == history.lastIndex),
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

private fun markerAt(call: CoroutineTimelineCall, batch: Int, lastBatch: Int): Marker = when {
    batch == call.endBatch -> when (call.completion) {
        TimelineCompletion.RETURNED -> Marker("✓", "Returned", "Result: ${displayValue(call.returnValue)}")
        TimelineCompletion.THREW -> Marker("!", "Threw", "Exception: ${call.exceptionMessage}")
        TimelineCompletion.CANCELLED -> Marker("⊘", "Cancellation completed", "The puzzle confirmed that this call was cancelled.")
        null -> Marker("")
    }
    batch == call.cancellationRequestedBatch -> Marker("×", "Cancellation requested", "Your coroutine asked the puzzle to cancel this call.")
    batch == call.startBatch -> Marker(
        "●",
        "Started",
        if (call.argument.isUnitValue()) "This call has no argument." else "Argument: ${displayValue(call.argument)}",
    )
    call.endBatch == null && batch == lastBatch -> Marker("…", "Still running", "The puzzle did not complete this call before the attempt ended.")
    batch in call.startBatch..(call.endBatch ?: lastBatch) -> Marker("━", "Running", "This call was in progress during this exchange.")
    else -> Marker("")
}

private fun displayValue(value: kotlinx.serialization.json.JsonElement?): String = when {
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

private suspend fun runKotlinBasicsPuzzle(
    server: WorkshopServer,
    stage: WorkshopStage.KotlinBasicsPuzzleStage,
    solutions: KotlinBasicsPuzzleSolutions,
) = when (stage) {
    PalindromeCheckTask -> server.kotlinBasicsPuzzle(stage).solve(solutions.palindromeCheckSolution)
    FindMinimumAgeOfUserTask -> server.kotlinBasicsPuzzle(stage).solve(solutions.minimumAgeSolution)
    FindOldestUserTask -> server.kotlinBasicsPuzzle(stage).solve(solutions.oldestUserSolution)
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
        os.contains("mac") -> listOf(listOf("open", "-a", "IntelliJ IDEA", file.path))
        os.contains("win") -> listOf(listOf("idea64.exe", file.path), listOf("idea.exe", file.path))
        else -> listOf(listOf("idea", file.path), listOf("intellij-idea", file.path))
    }
    if (commands.any { runCatching { ProcessBuilder(it).start().waitFor() == 0 }.getOrDefault(false) }) return null
    if (Desktop.isDesktopSupported() && runCatching { Desktop.getDesktop().open(file); true }.getOrDefault(false)) return null
    return "Could not launch IntelliJ IDEA. Open ${file.path} manually, or configure its desktop file association."
}
