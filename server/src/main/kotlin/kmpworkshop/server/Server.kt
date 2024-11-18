@file:Suppress("ReplaceToWithInfixForm")

package kmpworkshop.server

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kmpworkshop.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.math.absoluteValue
import kotlin.random.Random

fun main(): Unit = runBlocking {
    val serverState = MutableStateFlow(ServerState())
    val eventBus = Channel<ScheduledWorkshopEvent>(capacity = Channel.UNLIMITED)
    launch(Dispatchers.Default) {
        try {
            serveSingleService<WorkshopApiService> { coroutineContext ->
                workshopService(coroutineContext, serverState, onEvent = { launch { eventBus.send(it) } })
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            throw t
        }
    }
    val job = launch(Dispatchers.Default) {
        mainEventAndStoreLoopWritingTo(serverState, eventBus, onEvent = { launch { eventBus.send(it) } })
    }
    application {
        WorkshopWindow(
            onCloseRequest = {
                launch {
                    job.cancelAndJoin()
                    exitApplication()
                }
            },
            title = "KMP Workshop",
            onEvent = { launch { eventBus.send(it) } },
            serverState = serverState,
            serverUi = { state, onEvent -> ServerUi(state, onEvent) },
        )
    }
}

@Composable
fun WorkshopWindow(
    serverState: Flow<ServerState>,
    title: String,
    onCloseRequest: () -> Unit,
    onEvent: OnEvent,
    serverUi: @Composable (ServerState, onEvent: OnEvent) -> Unit,
) {
    Window(onCloseRequest = onCloseRequest, title = title) {
        var settingsIsOpen by remember { mutableStateOf(false) }
        MenuBar {
            Menu("Edit") {
                Item("Settings", shortcut = KeyShortcut(Key.Comma, meta = true), onClick = { settingsIsOpen = true })
            }
        }
        MaterialTheme {
            val state by serverState.collectAsState(initial = ServerState())
            if (settingsIsOpen) {
                SettingsDialog(
                    state.settings,
                    onDismiss = { settingsIsOpen = false },
                    onSettingsChange = { onEvent.schedule(SettingsChangeEvent(it)) },
                )
            }
            serverUi(state, onEvent)
        }
    }
}

fun workshopService(
    coroutineContext: CoroutineContext,
    serverState: Flow<ServerState>,
    onEvent: OnEvent,
): WorkshopApiService = object : WorkshopApiService {
    override suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult =
        onEvent.fire(RegistrationStartEvent(name))

    override suspend fun verifyRegistration(key: ApiKey): NameVerificationResult =
        onEvent.fire(RegistrationVerificationEvent(key))

    override suspend fun currentStage(): Flow<WorkshopStage> = serverState.map { it.currentStage }

    override suspend fun doPuzzleSolveAttempt(
        key: ApiKey,
        puzzleName: String,
        answers: Flow<JsonElement>,
    ): Flow<SolvingStatus> = flow {
        val puzzle =
            WorkshopStage
                .entries
                .firstOrNull { it.kotlinFile == puzzleName }
                ?.let { findPuzzleFor(it) } ?: run {
            println("Someone tried to request puzzle name: $puzzleName")
            emit(SolvingStatus.IncorrectInput)
            return@flow
        }

        var puzzleIndex = 0
        var lastInput: JsonElement? = null
        try {
            answers.onStart<JsonElement?> { emit(null) }.collect { answer ->
                if (answer != null) {
                    val expected = puzzle.getPuzzleOutputAsJsonElementAtIndex(puzzleIndex)
                    if (answer != expected) {
                        emit(SolvingStatus.Failed(lastInput!!, answer, expected))
                        return@collect
                    }
                    puzzleIndex++
                }
                if (puzzleIndex > puzzle.inAndOutputs.lastIndex) {
                    onEvent.fire(PuzzleFinishedEvent(Clock.System.now(), key, puzzleName)).also { emit(it) }
                } else {
                    val element = puzzle.getPuzzleInputAsJsonElementAtIndex(puzzleIndex)
                    emit(SolvingStatus.Next(element))
                    lastInput = element
                }
            }
        } catch (_: SerializationException) {
            emit(SolvingStatus.IncorrectInput)
        }
    }

    override suspend fun setSlider(key: ApiKey, suggestedRatio: Double): SlideResult =
        onEvent.fire(SliderSuggestionEvent(key, suggestedRatio))

    override suspend fun playPressiveGame(key: ApiKey, pressEvents: Flow<PressiveGamePressType>): Flow<String> =
        channelFlow {
            launch {
                pressEvents.collect { pressEvent ->
                    onEvent
                        .fire(PressiveGameEvent.Press(Clock.System.now(), Random.nextLong(), key, pressEvent))
                        ?.let { send(it) }
                }
            }

            serverState
                .map { state ->
                    when (val gameState = state.pressiveGameState) {
                        PressiveGameState.NotStarted -> "The Pressive game has not started yet! Please wait for the workshop host to start it."
                        is PressiveGameState.FirstGameDone -> "Waiting for the second game to start!"
                        is PressiveGameState.FirstGameInProgress -> gameState.states[key.stringRepresentation]?.toHint()
                            ?: "I'm so sorry! You have not been included in this game somehow :((. Please contact the workshop host!"
                        is PressiveGameState.SecondGameDone -> "Waiting for the third game to start!"
                        is PressiveGameState.SecondGameInProgress -> gameState.states[key.stringRepresentation]?.toHint(state)
                            ?: "I'm so sorry! You have not been included in this game somehow :((. Please contact the workshop host!"
                        PressiveGameState.ThirdGameDone-> "The game has finished! Thank you for playing!"
                        is PressiveGameState.ThirdGameInProgress -> ""
                    }
                }
                .distinctUntilChanged()
                .collect { send(it) }
        }

    override suspend fun discoGameInstructions(key: ApiKey): Flow<DiscoGameInstruction?> = serverState
        .map { it.discoGameState }
        .map { gameState ->
            when (gameState) {
                is DiscoGameState.Second.Done,
                is DiscoGameState.First.Done,
                is DiscoGameState.NotStarted -> null
                is DiscoGameState.Second.InProgress -> gameState
                    .instructionOrder
                    .getOrNull(gameState.progress)
                    ?.takeIf { it.participant == key }
                    ?.instruction
                is DiscoGameState.First.InProgress -> when (val state = gameState.states[key.stringRepresentation]) {
                    null,
                    is FirstDiscoGameParticipantState.Done -> null
                    is FirstDiscoGameParticipantState.InProgress -> state.colorAndInstructionState.current.instruction
                }
            }
        }
        .distinctUntilChanged()

    override suspend fun discoGamePress(key: ApiKey) {
        onEvent.schedule(DiscoGameEvent.GuessSubmissionEvent(key, Random.nextLong(), Clock.System.now()))
    }

    override suspend fun discoGameBackground(key: ApiKey): Flow<SerializableColor> = serverState
        .map { serverState ->
            when (val gameState = serverState.discoGameState) {
                is DiscoGameState.Second.Done,
                is DiscoGameState.First.Done,
                is DiscoGameState.NotStarted -> null
                is DiscoGameState.Second.InProgress -> gameState
                    .orderedParticipants
                    .firstOrNull { it.participant == key }
                    ?.color
                is DiscoGameState.First.InProgress -> when (val state = gameState.states[key.stringRepresentation]) {
                    null,
                    is FirstDiscoGameParticipantState.Done -> null
                    is FirstDiscoGameParticipantState.InProgress -> state.colorAndInstructionState.current.color
                }
            }.let { it ?: SerializableColor(0, 0, 0) }.applyingDimming(serverState.settings.dimmingRatio)
        }
        .distinctUntilChanged()

    override suspend fun pressiveGameBackground(key: ApiKey): Flow<SerializableColor?> = serverState
        .map { (it.pressiveGameState as? PressiveGameState.ThirdGameInProgress)?.participantThatIsBeingRung == key }
        .distinctUntilChanged()
        .map { isBeingRung -> SerializableColor(0, 0, 0).takeIf { isBeingRung } }

    override val coroutineContext = coroutineContext
}

internal fun SerializableColor.applyingDimming(dimmingRatio: Float): SerializableColor = transitionTo(
    other = if (dimmingRatio < 0) SerializableColor(0, 0, 0)
            else SerializableColor(255, 255, 255),
    ratio = dimmingRatio.absoluteValue,
)

internal const val PegWidth = 0.075
internal const val SliderGapWidth = 0.1

private fun <T, R> Puzzle<T, R>.getPuzzleInputAsJsonElementAtIndex(puzzleIndex: Int): JsonElement =
    Json.encodeToJsonElement(tSerializer, inAndOutputs[puzzleIndex].first)

private fun <T, R> Puzzle<T, R>.getPuzzleOutputAsJsonElementAtIndex(puzzleIndex: Int): JsonElement =
    Json.encodeToJsonElement(rSerializer, inAndOutputs[puzzleIndex].second)

private inline fun <reified T, reified R> puzzle(vararg inAndOutputs: Pair<T, R>): Puzzle<T, R> =
    Puzzle(inAndOutputs.asList(), serializer(), serializer())

private data class Puzzle<T, R>(
    val inAndOutputs: List<Pair<T, R>>,
    val tSerializer: KSerializer<T>,
    val rSerializer: KSerializer<R>,
)

private fun findPuzzleFor(stage: WorkshopStage): Puzzle<*, *>? = when (stage) {
    WorkshopStage.Registration,
    WorkshopStage.PressiveGameStage,
    WorkshopStage.DiscoGame,
    WorkshopStage.SliderGameStage -> null
    WorkshopStage.PalindromeCheckTask -> puzzle(
        "racecar" to true,
        "Racecar" to false,
        "radar" to true,
        "foo" to false,
        "abba" to true,
        "ABBA" to true,
    )
    WorkshopStage.FindMinimumAgeOfUserTask -> puzzle(
        listOf(SerializableUser("John", 18)) to 18,
        listOf(SerializableUser("John", 0)) to 0,
        listOf(
            SerializableUser("John", 0),
            SerializableUser("Jane", 10),
        ) to 0,
        listOf(
            SerializableUser("John", 10),
            SerializableUser("Jane", 100),
        ) to 10,
        listOf(
            SerializableUser("John", 100),
            SerializableUser("Jane", 10),
        ) to 10,
    )
    WorkshopStage.FindOldestUserTask -> puzzle(
        listOf(SerializableUser("John", 18)) to SerializableUser("John", 18),
        listOf(SerializableUser("John", 0)) to SerializableUser("John", 0),
        listOf(
            SerializableUser("John", 0),
            SerializableUser("Jane", 10),
        ) to SerializableUser("Jane", 10),
        listOf(
            SerializableUser("John", 10),
            SerializableUser("Jane", 100),
        ) to SerializableUser("Jane", 100),
        listOf(
            SerializableUser("John", 100),
            SerializableUser("Jane", 10),
        ) to SerializableUser("John", 100),
    )
}

inline fun <T : R, R> T.applyIf(predicate: (T) -> Boolean, mapper: (T) -> R): R = if (predicate(this)) mapper(this) else this
