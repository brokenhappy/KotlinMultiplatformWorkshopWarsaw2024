@file:Suppress("FunctionName")
package kmpworkshop.server

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight.Companion.Bold
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kmpworkshop.common.ApiKey
import kmpworkshop.common.SerializableColor
import kmpworkshop.common.WorkshopStage
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun SettingsDialog(settings: ServerSettings, onDismiss: () -> Unit, onSettingsChange: (ServerSettings) -> Unit) {
    var currentSettings by remember(settings) { mutableStateOf(settings) }
    var autoSave by remember { mutableStateOf(false) }

    LaunchedEffect(currentSettings, autoSave) {
        if (autoSave && currentSettings != settings) {
            onSettingsChange(currentSettings)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(2.dp, shape = RoundedCornerShape(4.dp), color = Color.DarkGray)
                .background(Color.White)
                .padding(16.dp)
            ,
        ) {
            Row {
                Text("Color dimming: ")
                Slider(
                    currentSettings.dimmingRatio,
                    onValueChange = { currentSettings = settings.copy(dimmingRatio = it) },
                    valueRange = -1f..1f,
                )
            }
            Row {
                Text("Auto apply changes: ")
                Checkbox(autoSave, onCheckedChange = { autoSave = it })
            }

            Row {
                Button(onClick = { currentSettings = ServerSettings() }) {
                    Text("Reset")
                }
                Button(onClick = { onDismiss() }) {
                    Text("Close")
                }
                if (!autoSave) {
                    Button(onClick = { onSettingsChange(currentSettings) }, enabled = currentSettings != settings) {
                        Text("Save")
                    }
                }
            }
        }
    }
}


@Composable
fun ServerUi(state: ServerState, onEvent: OnEvent) {
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
    onEvent: OnEvent,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        TopButton(
            text = when (state.discoGameState) {
                is DiscoGameState.First.Done -> "Restart First game"
                is DiscoGameState.First.InProgress -> "Stop First game"
                is DiscoGameState.NotStarted,
                is DiscoGameState.Second -> "Start First game"
            },
            onClick = {
                onEvent.schedule(
                    when (state.discoGameState) {
                        is DiscoGameState.First.InProgress -> DiscoGameEvent.StopFirst(Clock.System.now())
                        is DiscoGameState.First.Done -> DiscoGameEvent.RestartFirst(Clock.System.now(), Random.nextLong())
                        is DiscoGameState.NotStarted,
                        is DiscoGameState.Second -> DiscoGameEvent.StartFirst(Clock.System.now(), Random.nextLong())
                    }
                )
            },
        )
        TopButton(
            text = when (state.discoGameState) {
                is DiscoGameState.Second.Done -> "Restart Second game"
                is DiscoGameState.Second.InProgress -> "Stop Second game"
                is DiscoGameState.NotStarted,
                is DiscoGameState.First -> "Start Second game"
            },
            onClick = {
                onEvent.schedule(
                    when (state.discoGameState) {
                        is DiscoGameState.Second.InProgress -> DiscoGameEvent.StopSecond
                        is DiscoGameState.Second.Done -> DiscoGameEvent.RestartSecond(Random.nextLong())
                        is DiscoGameState.NotStarted -> DiscoGameEvent.StartSecond(Random.nextLong())
                        is DiscoGameState.First -> DiscoGameEvent.StartSecond(Random.nextLong())
                    }
                )
            },
        )
    }
    when (val gameState = state.discoGameState) {
        is DiscoGameState.Second.Done -> Text("Second game finished!")
        is DiscoGameState.NotStarted -> Text("Second game has not started yet!")
        is DiscoGameState.Second.InProgress -> Column(modifier = Modifier.padding(16.dp)) {
            BigProgressBar(gameState.progress / gameState.orderedParticipants.size.toFloat())
            state
                .scheduledEvents
                .firstOrNull { it.event is DiscoGameEvent.SecondPressTimeout }
                ?.let { CountDownProgressBar(it.time) }
                ?: BigProgressBar(0f)
            gameState
                .orderedParticipants
                .chunked(gameState.width.takeIf { it > 0 } ?: 1)
                .forEach { row ->
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        for ((_, color) in row) {
                            Spacer(
                                modifier = Modifier.weight(1f)
                                    .fillMaxHeight()
                                    .background(color.applyingDimming(state.settings.dimmingRatio).toComposeColor())
                                ,
                            )
                        }
                        if (row.size < gameState.width) {
                            Spacer(modifier = Modifier.weight((gameState.width - row.size).toFloat()))
                        }
                    }
                }
        }
        is DiscoGameState.First.Done -> Submissions(gameState.submissions)
        is DiscoGameState.First.InProgress -> Column {
            Box {
                Text(
                    (gameState.target.current.instruction?.char ?: '·').toString(),
                    modifier = Modifier.background(
                        color = gameState.target.current.color
                            .applyingDimming(state.settings.dimmingRatio)
                            .toComposeColor(),
                    ),
                    fontSize = 250.sp,
                )
            }
            Submissions(gameState.toSubmissionsIn(state))
        }
    }
}

private fun kmpworkshop.common.SerializableColor.toComposeColor(): Color = Color(red, green, blue)

@Composable
private fun PressiveGame(state: ServerState, onEvent: OnEvent) {
    Column(modifier = Modifier.padding(16.dp)) {
        TopButton("Start First game", onClick = { onEvent.schedule(PressiveGameEvent.StartFirst(Clock.System.now(), Random.nextLong())) })
        TopButton(
            "Start Second game",
            enabled = state.participants.size % 2 == 0,
            onClick = { onEvent.schedule(PressiveGameEvent.StartSecond(Random.nextLong())) },
        )
        TopButton("Start Third game", onClick = { onEvent.schedule(PressiveGameEvent.StartThird(Random.nextLong())) })
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
                progress = ((time - frameNanosToInstant(frameTimeNanos)) / secondDiscoGamePressTimeout).toFloat().coerceIn(0f, 1f)
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
fun SerializableColor.transitionTo(other: SerializableColor, ratio: Float): SerializableColor = SerializableColor(
    red = (this.red * (1 - ratio) + other.red * ratio).roundToInt(),
    green = (this.green * (1 - ratio) + other.green * ratio).roundToInt(),
    blue = (this.blue * (1 - ratio) + other.blue * ratio).roundToInt(),
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
private fun SliderGame(state: ServerState, onEvent: OnEvent) {
    // TODO: Names don't line up with sliders.
    when (val gameState = state.sliderGameState) {
        SliderGameState.NotStarted -> Column(modifier = Modifier.padding(16.dp)) {
            TopButton("Start game") { onEvent.schedule(SliderGameEvent.Start(Random.nextLong())) }
        }
        is SliderGameState.InProgress -> Column(modifier = Modifier.padding(16.dp)) {
            TopButton("Stop game") { onEvent.schedule(SliderGameEvent.Finished(gameState)) }
            UninteractiveSliderGame(gameState, getParticipant = { state.getParticipantBy(it) })
        }
        is SliderGameState.Done -> Column(modifier = Modifier.padding(16.dp)) {
            TopButton("Restart game") { onEvent.schedule(SliderGameEvent.Restart(Random.nextLong())) }
            UninteractiveSliderGame(gameState.lastState, getParticipant = { state.getParticipantBy(it) })
        }
    }
}

// @TestOnly public!!!
context(Random)
fun binaryMoreCodeIdentifiers(count: Int): List<String> = count
    .nextPowerOfTwo()
    .let { totalBits ->
        val width = when (count) {
            1 -> 1
            else -> Int.SIZE_BITS - totalBits.countLeadingZeroBits() - 1
        }
        (0..<totalBits)
            .shuffled(this@Random)
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
    Row(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
private fun Puzzle(state: ServerState, puzzleName: String, onEvent: OnEvent) {
    val puzzleState = state.puzzleStates[puzzleName] ?: PuzzleState.Unopened
    when (puzzleState) {
        PuzzleState.Unopened -> {
            Row {
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onEvent.schedule(PuzzleStartEvent(puzzleName, Clock.System.now())) }) {
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
    Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        BasicText(text = "Number of completions: ${submissions.completedSubmissions.size}/${submissions.participants.size}")
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

@Serializable
data class Submissions(
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
private fun StageTopBar(stage: WorkshopStage, onEvent: OnEvent) {
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
                        DropdownMenuItem(onClick = { expanded = false; onEvent.schedule(StageChangeEvent(it)) }) {
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
    onEvent: OnEvent,
    offset: Int,
    key: Key, // TODO: Make work?
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        enabled = stage.moving(offset) != null,
        onClick = { onEvent.schedule(StageChangeEvent(stage.moving(offset)!!)) },
        content = content,
    )
}

private fun WorkshopStage.moving(offset: Int): WorkshopStage? =
    WorkshopStage.entries.getOrNull(ordinal + offset)

@Composable
private fun Registration(
    state: ServerState,
    onEvent: OnEvent,
) {
    Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        BasicText(text = "Number of verified participants: ${state.participants.size}")
        state.participants.forEach { participant ->
            Row(modifier = Modifier.padding(8.dp)) {
                BasicText(text = participant.name)
                BasicText(
                    text = "Verified",
                    modifier = Modifier.padding(start = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onEvent.schedule(ParticipantDeactivationEvent(participant)) }) {
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
                Button(onClick = { onEvent.schedule(ParticipantReactivationEvent(participant, Random.nextLong())) }) {
                    Text("Activate")
                }
                Button(onClick = { onEvent.schedule(ParticipantRemovalEvent(participant)) }) {
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
                Button(onClick = { onEvent.schedule(ParticipantRejectionEvent(participant)) }) {
                    Text("Reject")
                }
            }
        }
    }
}
