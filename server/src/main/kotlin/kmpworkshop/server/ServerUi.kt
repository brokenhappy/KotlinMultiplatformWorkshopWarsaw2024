@file:Suppress("FunctionName")
package kmpworkshop.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation.Vertical
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight.Companion.Bold
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kmpworkshop.common.ApiKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun ServerUi(serverState: MutableStateFlow<ServerState>, onEvent: (WorkshopEvent) -> Unit) {
    val state by produceState(initialValue = ServerState()) {
        serverState.collect { value = it }
    }
    val scope = rememberCoroutineScope()

    ServerUi(state, onStateChange = { stateUpdater ->
        scope.launch(Dispatchers.Default) { serverState.update { stateUpdater(it) } }
    }, onEvent = onEvent)
}

@Composable
fun ServerUi(state: ServerState, onStateChange: ((ServerState) -> ServerState) -> Unit, onEvent: (WorkshopEvent) -> Unit) {
    Column {
        // TODO: Start first pressive tick event when switching to Pressive game!
        StageTopBar(state.currentStage, onEvent)
        when (state.currentStage) {
            WorkshopStage.Registration -> Registration(state, onEvent)
            WorkshopStage.PalindromeCheckTask -> Puzzle(state, WorkshopStage.PalindromeCheckTask.kotlinFile, onEvent)
            WorkshopStage.FindMinimumAgeOfUserTask -> Puzzle(state, WorkshopStage.FindMinimumAgeOfUserTask.kotlinFile, onEvent)
            WorkshopStage.FindOldestUserTask -> Puzzle(state, WorkshopStage.FindOldestUserTask.kotlinFile, onEvent)
            WorkshopStage.SliderGameStage -> SliderGame(state, onEvent)
            WorkshopStage.PressiveGameStage -> PressiveGame(state, onEvent)
            WorkshopStage.DiscoGame -> DiscoGame(state, onEvent)
        }
    }
}

@Composable
private fun DiscoGame(
    state: ServerState,
    onEvent: (WorkshopEvent) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        TopButton(
            text = when (state.discoGameState) {
                DiscoGameState.Done -> "Restart game"
                is DiscoGameState.InProgress -> "Stop game"
                DiscoGameState.NotStarted -> "Start game"
            },
            onClick = {
                onEvent(
                    when (state.discoGameState) {
                        is DiscoGameState.InProgress -> DiscoGameEvent.Stop
                        DiscoGameState.Done -> DiscoGameEvent.Restart
                        DiscoGameState.NotStarted -> DiscoGameEvent.Start
                    }
                )
            },
        )
    }
    when (val gameState = state.discoGameState) {
        DiscoGameState.Done -> Text("Game finished!")
        DiscoGameState.NotStarted -> Text("Game has not started yet!")
        is DiscoGameState.InProgress -> Column(modifier = Modifier.padding(16.dp)) {
            BigProgressBar(gameState.progress / gameState.orderedParticipants.size.toFloat())
            state
                .scheduledEvents
                .firstOrNull { it.type is TimedEventType.DiscoGamePressTimeoutEvent }
                ?.let { CountDownProgressBar(it.time) }
                ?: BigProgressBar(0f)
            gameState
                .orderedParticipants
                .chunked(gameState.width)
                .forEach { row ->
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        for ((_, color) in row) {
                            Spacer(modifier = Modifier.weight(1f).fillMaxHeight().background(color.toComposeColor()))
                        }
                        if (row.size < gameState.width) {
                            Spacer(modifier = Modifier.weight((gameState.width - row.size).toFloat()))
                        }
                    }
                }
        }
    }
}

private fun kmpworkshop.common.Color.toComposeColor(): Color = Color(red, green, blue)

@Composable
private fun PressiveGame(state: ServerState, onEvent: (WorkshopEvent) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        TopButton("Start First game", onClick = { onEvent(PressiveGameEvent.StartFirst) })
        TopButton(
            "Start Second game",
            enabled = state.participants.size % 2 == 0,
            onClick = { onEvent(PressiveGameEvent.StartSecond) },
        )
        TopButton("Start Third game", onClick = { onEvent(PressiveGameEvent.StartThird) })
    }
    when (val gameState = state.pressiveGameState) {
        PressiveGameState.NotStarted -> Unit
        is PressiveGameState.FirstGameDone -> Submissions(gameState.asSubmissions(state.participants))
        is PressiveGameState.FirstGameInProgress -> Submissions(gameState.asSubmissions(state.participants))
        PressiveGameState.SecondGameDone -> SecondOrThirdPressiveGame(1f)
        is PressiveGameState.SecondGameInProgress ->
            SecondOrThirdPressiveGame(gameState.progress.toFloat() / gameState.states.size)
        PressiveGameState.ThirdGameDone -> SecondOrThirdPressiveGame(1f)
        is PressiveGameState.ThirdGameInProgress ->
            SecondOrThirdPressiveGame(gameState.progress.toFloat() / gameState.order.size)
    }
}

@Composable
private fun SecondOrThirdPressiveGame(progress: Float) {
    Row(modifier = Modifier.padding(horizontal = 32.dp)) {
        Text("Progress: ")
        BigProgressBar(progress)
    }
}

@Composable
private fun CountDownProgressBar(time: Instant) {
    var progress by remember { mutableStateOf(1f) }

    LaunchedEffect(time) {
        val startInstant = Clock.System.now()
        val startNanos = withFrameNanos { it }
        fun frameNanosToInstant(frameNanos: Long): Instant = startInstant + (frameNanos - startNanos).nanoseconds
        while (true) {
            withFrameNanos { frameTimeNanos ->
                progress = ((time - frameNanosToInstant(frameTimeNanos)) / discoGamePressTimeout).toFloat().coerceIn(0f, 1f)
            }
        }
    }

    BigProgressBar(progress)
}

@Composable
private fun BigProgressBar(progress: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = Color.Black),
    ) {
        if (progress > 0f) {
            Spacer(
                modifier = Modifier
                    .height(32.dp)
                    .weight(progress)
                    .background(Color.Red.transitionTo(Color.Green, ratio = progress))
            )
        }
        if (progress < 1f) {
            Spacer(modifier = Modifier.height(32.dp).weight(1 - progress))
        }
    }
}

fun Color.transitionTo(other: Color, ratio: Float): Color = Color(
    red = this.red * (1 - ratio) + other.red * ratio,
    green = this.green * (1 - ratio) + other.green * ratio,
    blue = this.blue * (1 - ratio) + other.blue * ratio,
    alpha = this.alpha * (1 - ratio) + other.alpha * ratio
)


private fun PressiveGameState.FirstGameInProgress.asSubmissions(participants: List<Participant>) = Submissions(
    startTime = startTime,
    participants = participants,
    completedSubmissions = states
        .mapNotNull { (key, state) -> state.finishTime?.let { ApiKey(key) to it } }
        .toMap()
)

private fun PressiveGameState.FirstGameDone.asSubmissions(participants: List<Participant>) = Submissions(
    startTime = startTime,
    participants = participants,
    completedSubmissions = finishTimes.mapKeys { (key, _) -> ApiKey(key) }
)

@Composable
private fun SliderGame(state: ServerState, onEvent: (WorkshopEvent) -> Unit) {
    // TODO: Names don't line up with sliders.
    when (val gameState = state.sliderGameState) {
        SliderGameState.NotStarted -> Column(modifier = Modifier.padding(16.dp)) {
            TopButton("Start game") { onEvent(SliderGameEvent.Start) }
        }
        is SliderGameState.InProgress -> Column(modifier = Modifier.padding(16.dp)) {
            TopButton("Stop game") { onEvent(SliderGameEvent.Finished(gameState)) }
            UninteractiveSliderGame(gameState, getParticipant = { state.getParticipantBy(it) })
        }
        is SliderGameState.Done -> Column(modifier = Modifier.padding(16.dp)) {
            TopButton("Restart game") { onEvent(SliderGameEvent.Restart) }
            UninteractiveSliderGame(gameState.lastState, getParticipant = { state.getParticipantBy(it) })
        }
    }
}

// @TestOnly public!!!
fun binaryMoreCodeIdentifiers(count: Int, random: Random = Random): List<String> = count
    .nextPowerOfTwo()
    .let { totalBits ->
        val width = when (count) {
            1 -> 1
            else -> totalBits.countLeadingZeroBits().let { leadingBits ->
                Int.SIZE_BITS - leadingBits - 1
            }
        }
        (0..<totalBits)
            .shuffled(random)
            .take(count)
            .map { it.binaryAsMorseCode().padEnd(width, '.') }
    }

// Thanks, AI assistant!
private fun Int.nextPowerOfTwo(): Int {
    if (this <= 0) return 1
    var value = this - 1
    value = value or (value shr 1)
    value = value or (value shr 2)
    value = value or (value shr 4)
    value = value or (value shr 8)
    value = value or (value shr 16)
    return value + 1
}

// 3 => 11 => --
// 4 => 100 => -..
fun Int.binaryAsMorseCode(): String =
    if (this == 0) "" else (if (this % 2 == 0) "." else "-") + (this / 2).binaryAsMorseCode()

@Composable
private fun UninteractiveSliderGame(gameState: SliderGameState.InProgress, getParticipant: (ApiKey) -> Participant) {
    Row {
        fun Modifier.weight(d: Double): Modifier = if (d == .0) this else this.weight(d.toFloat())
        Column {
            Spacer(modifier = Modifier.height(32.dp))
            gameState.participantStates.map { getParticipant(ApiKey(it.key)) }.forEach { participant ->
                Text("${participant.name}: ", modifier = Modifier.height(32.dp))
//                Spacer(modifier = Modifier.width(16.dp))
            }
        }
        Box {
            Column {
                Spacer(modifier = Modifier.height(32.dp))
                gameState.participantStates.values.forEach { slider ->
                    Row(modifier = Modifier.height(32.dp)) {
                        val leftOffset = slider.position * 2 / 3 + slider.gapOffset / 3
                        Spacer(
                            modifier = Modifier
                                .weight(leftOffset)
                                .height(32.dp)
                                .background(Color.Black)
                        )
                        Spacer(modifier = Modifier.weight(SliderGapWidth).height(32.dp))
                        Spacer(
                            modifier = Modifier
                                .weight(1.0 - SliderGapWidth - leftOffset)
                                .height(32.dp)
                                .background(Color.Black)
                        )
                    }
                }
            }
            Column {
                Spacer(modifier = Modifier.height(32.dp * (gameState.pegLevel + 1)))
                Row {
                    Spacer(modifier = Modifier.weight(1.0 + gameState.pegPosition))
                    Spacer(modifier = Modifier.weight(PegWidth * 3).background(Color.Red).height(32.dp))
                    Spacer(modifier = Modifier.weight(2.0 - gameState.pegPosition - PegWidth * 3))
                }
            }
        }
    }
}

@Composable
private fun TopButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row {
        Spacer(modifier = Modifier.weight(1f))
        Button(enabled = enabled, onClick = onClick) {
            Text(text)
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Puzzle(state: ServerState, puzzleName: String, onEvent: (WorkshopEvent) -> Unit) {
    val puzzleState = state.puzzleStates[puzzleName] ?: PuzzleState.Unopened
    when (puzzleState) {
        PuzzleState.Unopened -> {
            Row {
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onEvent(PuzzleStartEvent(puzzleName, Clock.System.now())) }) {
                    Text("Open puzzle!")
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        is PuzzleState.Opened -> Submissions(puzzleState.asSubmissions(state.participants))
    }
}

private fun ServerState.getParticipantBy(key: ApiKey): Participant = participants.first { it.apiKey == key }

@Composable
private fun Submissions(submissions: Submissions) {
    Column(modifier = Modifier.padding(16.dp).scrollable(rememberScrollState(), orientation = Vertical)) {
        BasicText(text = "Number of completions: ${submissions.completedSubmissions.size}")
        for ((apiKey, timeOfCompletion) in submissions.completedSubmissions.entries.sortedBy { it.value }) {
            Row(modifier = Modifier.padding(8.dp)) {
                BasicText(text = submissions.participants.first { it.apiKey == apiKey }.name)
                val duration = timeOfCompletion - submissions.startTime
                BasicText(
                    text = "Took: ${formatDuration(duration)}",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

private data class Submissions(
    val startTime: Instant,
    val participants: List<Participant>,
    val completedSubmissions: Map<ApiKey, Instant>,
)

private fun PuzzleState.Opened.asSubmissions(participants: List<Participant>): Submissions = Submissions(
    startTime,
    participants,
    submissions.mapKeys { (key, _) -> ApiKey(key) },
)

private fun formatDuration(duration: Duration): String = when {
    duration < 1.seconds -> "${duration.inWholeMilliseconds}ms"
    duration < 1.minutes -> "${duration.inWholeSeconds}s ${duration.minus(duration.inWholeSeconds.seconds).inWholeMilliseconds}ms"
    else -> "${duration.inWholeMinutes}m ${duration.minus(duration.inWholeMinutes.minutes).inWholeSeconds}s"
}

@Composable
private fun StageTopBar(stage: WorkshopStage, onEvent: (WorkshopEvent) -> Unit) {
    Row(modifier = Modifier.padding(16.dp)) {
        Row {
            MoveStageButton(stage, onEvent, -1, Key.DirectionLeft) {
                Text("<")
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                var expanded by remember { mutableStateOf(false) }
                ClickableText(
                    text = AnnotatedString("Go to file: ${stage.kotlinFile}"),
                    style = TextStyle(fontSize = 20.sp, fontWeight = Bold),
                    onClick = { expanded = true }
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    WorkshopStage.entries.forEach {
                        DropdownMenuItem(onClick = { expanded = false; onEvent(StageChangeEvent(it)) }) {
                            Text(it.kotlinFile)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            MoveStageButton(stage, onEvent, 1, Key.DirectionRight) {
                Text(">")
            }
        }
    }
    Divider(
        modifier = Modifier.padding(bottom = 8.dp),
        color = Color.Black,
        thickness = 1.dp,
    )
}

@Composable
private fun MoveStageButton(
    stage: WorkshopStage,
    onEvent: (WorkshopEvent) -> Unit,
    offset: Int,
    key: Key, // TODO: Make work?
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        enabled = stage.moving(offset) != null,
        onClick = { onEvent(StageChangeEvent(stage.moving(offset)!!)) },
        content = content,
    )
}

private fun WorkshopStage.moving(offset: Int): WorkshopStage? =
    WorkshopStage.entries.getOrNull(ordinal + offset)

@Composable
private fun Registration(
    state: ServerState,
    onEvent: (WorkshopEvent) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp).scrollable(rememberScrollState(), orientation = Vertical)) {
        BasicText(text = "Number of verified participants: ${state.participants.size}")
        state.participants.forEach { participant ->
            Row(modifier = Modifier.padding(8.dp)) {
                BasicText(text = participant.name)
                BasicText(
                    text = "Verified",
                    modifier = Modifier.padding(start = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onEvent(ParticipantDeactivationEvent(participant)) }) {
                    Text("Deactivate")
                }
            }
        }
        state.deactivatedParticipants.forEach { participant ->
            Row(modifier = Modifier.padding(8.dp)) {
                BasicText(text = participant.name)
                BasicText(
                    text = "Deactivated",
                    modifier = Modifier.padding(start = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onEvent(ParticipantReactivationEvent(participant)) }) {
                    Text("Activate")
                }
                Button(onClick = { onEvent(ParticipantRemovalEvent(participant)) }) {
                    Text("Delete")
                }
            }
        }
        state.unverifiedParticipants.forEach { participant ->
            Row(modifier = Modifier.padding(8.dp)) {
                BasicText(text = participant.name)
                BasicText(
                    text = "Pending",
                    modifier = Modifier.padding(start = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onEvent(ParticipantRejectionEvent(participant)) }) {
                    Text("Reject")
                }
            }
        }
    }
}
