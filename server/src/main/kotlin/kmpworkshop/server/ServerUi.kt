@file:Suppress("FunctionName")
package kmpworkshop.server

import androidx.compose.foundation.background
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
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun ServerUi() {
    val state by produceState(initialValue = ServerState()) {
        serverState().collect { value = it }
    }
    val scope = rememberCoroutineScope()

    ServerUi(state, onStateChange = { stateUpdater -> scope.launch { updateServerState(stateUpdater) } })
}

@Composable
private fun ServerUi(state: ServerState, onStateChange: ((ServerState) -> ServerState) -> Unit) {
    Column {
        StageTopBar(state.currentStage, onStageChange = { newStage -> onStateChange { it.copy(currentStage = newStage) } })
        when (state.currentStage) {
            WorkshopStage.Registration -> Registration(state, onStateChange)
            WorkshopStage.PalindromeCheckTask -> Puzzle(state, WorkshopStage.PalindromeCheckTask.kotlinFile, onStateChange)
            WorkshopStage.FindMinimumAgeOfUserTask -> Puzzle(state, WorkshopStage.FindMinimumAgeOfUserTask.kotlinFile, onStateChange)
            WorkshopStage.FindOldestUserTask -> Puzzle(state, WorkshopStage.FindOldestUserTask.kotlinFile, onStateChange)
            WorkshopStage.SliderGameStage -> SliderGame(state, onStateChange)
        }
    }
}

@Composable
private fun SliderGame(state: ServerState, onStateChange: ((ServerState) -> ServerState) -> Unit) {
    when (val gameState = state.sliderGameState) {
        SliderGameState.NotStarted -> Column(modifier = Modifier.padding(16.dp)) {
            TopButton("Start game") { onStateChange { it.startingNewGame() } }
        }
        is SliderGameState.InProgress -> Column(modifier = Modifier.padding(16.dp)) {
            TopButton("Stop game") { onStateChange { it.copy(sliderGameState = SliderGameState.Done(gameState)) } }
            UninteractiveSliderGame(gameState, getParticipant = { it -> state.getParticipantBy(it) })
        }
        is SliderGameState.Done -> Column(modifier = Modifier.padding(16.dp)) {
            TopButton("Restart game") { onStateChange { it.startingNewGame() } }
            UninteractiveSliderGame(gameState.lastState, getParticipant = { it -> state.getParticipantBy(it) })
        }
    }
}

private fun ServerState.startingNewGame(): ServerState = copy(sliderGameState = newSliderGame(participants.map { it.apiKey }))

@Composable
private fun UninteractiveSliderGame(gameState: SliderGameState.InProgress, getParticipant: (ApiKey) -> Participant) {
    Row {
        fun Modifier.weight(d: Double): Modifier = if (d == .0) this else this.weight(d.toFloat())
        Column {
            Spacer(modifier = Modifier.height(32.dp))
            gameState.participantStates.map { getParticipant(ApiKey(it.key)) }.forEach { participant ->
                Text("${participant.name}: ")
                Spacer(modifier = Modifier.width(16.dp))
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
private fun TopButton(text: String, onClick: () -> Unit) {
    Row {
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onClick) {
            Text(text)
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

private fun newSliderGame(participants: List<ApiKey>): SliderGameState.InProgress =
    newSliderGame(participants, Random.nextDouble(1.0 - PegWidth * 3))

private fun newSliderGame(participants: List<ApiKey>, pegPosition: Double): SliderGameState.InProgress =
    SliderGameState.InProgress(
        participantStates = participants.associate {
            it.stringRepresentation to generateSequence {
                SliderState(gapOffset = Random.nextDouble(1.0 - SliderGapWidth * 3), position = 0.5)
            }.first { !it.letsThroughPegPositionedAt(pegPosition) }
        }.toSortedMap(),
        pegPosition = pegPosition,
        pegLevel = -1,
    )

internal fun SliderState.letsThroughPegPositionedAt(pegPosition: Double): Boolean =
    position in positionRangeInWhichPegWouldFallThrough(pegPosition)

internal fun SliderState.positionRangeInWhichPegWouldFallThrough(pegPosition: Double): ClosedFloatingPointRange<Double> =
    ((pegPosition - gapOffset + 1.0) / 2)
        .let { end -> (end - (SliderGapWidth - PegWidth) * 3 / 2)..end }

@Composable
private fun Puzzle(state: ServerState, puzzleName: String, onStateChange: ((ServerState) -> ServerState) -> Unit) {
    val puzzleState = state.puzzleStates[puzzleName] ?: PuzzleState.Unopened
    when (puzzleState) {
        PuzzleState.Unopened -> {
            Row {
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = {
                    onStateChange {
                        it.copy(puzzleStates =  it.puzzleStates + (puzzleName to PuzzleState.Opened(
                            startTime = Clock.System.now(),
                            submissions = emptyMap()
                        )))
                    }
                }) {
                    Text("Open puzzle!")
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        is PuzzleState.Opened -> OpenedPuzzle(puzzleState) { key -> state.getParticipantBy(key) }
    }
}

private fun ServerState.getParticipantBy(key: ApiKey): Participant = participants.first { it.apiKey == key }

@Composable
private fun OpenedPuzzle(state: PuzzleState.Opened, getParticipant: (ApiKey) -> Participant) {
    Column(modifier = Modifier.padding(16.dp).scrollable(rememberScrollState(), orientation = Vertical)) {
        BasicText(text = "Number of completions: ${state.submissions.size}")
        for ((apiKey, timeOfCompletion) in state.submissions) {
            val participant = getParticipant(ApiKey(apiKey))
            Row(modifier = Modifier.padding(8.dp)) {
                BasicText(text = participant.name)
                val duration = timeOfCompletion - state.startTime
                BasicText(
                    text = "Took: ${formatDuration(duration)}",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

private fun formatDuration(duration: Duration): String = when {
    duration < 1.seconds -> "${duration.inWholeMilliseconds}ms"
    duration < 1.minutes -> "${duration.inWholeSeconds}s ${duration.minus(duration.inWholeSeconds.seconds).inWholeMilliseconds}ms"
    else -> "${duration.inWholeMinutes}m ${duration.minus(duration.inWholeMinutes.minutes).inWholeSeconds}s"
}

@Composable
private fun StageTopBar(stage: WorkshopStage, onStageChange: (WorkshopStage) -> Unit) {
    Row(modifier = Modifier.padding(16.dp)) {
        Row {
            MoveStageButton(stage, onStageChange, -1, Key.DirectionLeft) {
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
                        DropdownMenuItem(onClick = { expanded = false; onStageChange(it) }) {
                            Text(it.kotlinFile)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            MoveStageButton(stage, onStageChange, 1, Key.DirectionRight) {
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
    onStageChange: (WorkshopStage) -> Unit,
    offset: Int,
    key: Key, // TODO: Make work?
    content: @Composable RowScope.() -> Unit
) {
    Button(
        enabled = stage.moving(offset) != null,
        onClick = { onStageChange(stage.moving(offset)!!) },
        content = content,
    )
}

private fun WorkshopStage.moving(offset: Int): WorkshopStage? =
    WorkshopStage.entries.getOrNull(ordinal + offset)

@Composable
private fun Registration(
    state: ServerState,
    onStateChange: ((ServerState) -> ServerState) -> Unit
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
                Button(onClick = { onStateChange { it.removeParticipant(participant) } }) {
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
                Button(onClick = { onStateChange { it.copy(unverifiedParticipants = it.unverifiedParticipants - participant) } }) {
                    Text("Reject")
                }
            }
        }
    }
}

private fun ServerState.removeParticipant(participant: Participant): ServerState = copy(
    participants = participants - participant,
    puzzleStates = puzzleStates.mapValues { (_, puzzleState) ->
        when (puzzleState) {
            PuzzleState.Unopened -> puzzleState
            is PuzzleState.Opened -> puzzleState
                .copy(submissions = puzzleState.submissions - participant.apiKey.stringRepresentation)
        }
    }
)