@file:Suppress("ReplaceToWithInfixForm")
@file:OptIn(ExperimentalTime::class)

package kmpworkshop.server

import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleEndpointAnswer.CallAnswered
import kmpworkshop.common.WorkshopStage.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import workshop.adminaccess.*
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

suspend fun main() {
    hostServer()
}

suspend fun hostServer(): Nothing = withContext(Dispatchers.Default) {
    val serverState = MutableStateFlow(ServerState())
    val eventBus = Channel<ScheduledWorkshopEvent>(capacity = Channel.UNLIMITED)

    launch {
        serve(
            rpcService { workshopService(serverState, onEvent = { eventBus.trySend(it) }) },
            rpcService { adminAccess(serverState, onEvent = { eventBus.trySend(it) }) },
        )
    }
    coroutineScope {
        mainEventLoopWithCommittedStateChannelWritingTo(
            serverState,
            eventBus,
            onEvent = { launch { eventBus.send(it) } }
        ) { initialState, channel ->
            withBackupLoop(initialState, channel) { backupRequests, trailingBackup ->
                val flow = backupRequests.consumeAsFlow()
                    .shareIn(this, started = SharingStarted.Eagerly, replay = 10)
                val channelCopy = Channel<BackupRequest>()
                launch {
                    flow.collect { event ->
                        channelCopy.send(event)
                    }
                }

                store(channelCopy)
            }
        }
    }
}

private fun workshopService(
    serverState: Flow<ServerState>,
    onEvent: OnEvent,
): WorkshopApiService = object : WorkshopApiService {
    override suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult =
        onEvent.fire(RegistrationStartEvent(name, Random.nextLong()))

    override suspend fun verifyRegistration(key: ApiKey): NameVerificationResult =
        onEvent.fire(RegistrationVerificationEvent(key))

    override fun currentStage(): Flow<WorkshopStage> = serverState.map { it.currentStage }

    override fun doPuzzleSolveAttempt(
        key: ApiKey,
        puzzleName: String,
        answers: Flow<JsonElement>,
    ): Flow<SolvingStatus> = flow {
        val puzzle = WorkshopStage
            .entries
            .firstOrNull { it.kotlinFile == puzzleName }
            ?.let { findPuzzleFor(it) }
            ?: run {
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
                    emit(
                        when (onEvent.fire(PuzzleFinishedEvent(Clock.System.now(), key, puzzleName))) {
                            PuzzleCompletionResult.AlreadySolved -> SolvingStatus.AlreadySolved
                            PuzzleCompletionResult.Done -> SolvingStatus.Done
                            PuzzleCompletionResult.PuzzleNotOpenedYet -> SolvingStatus.PuzzleNotOpenedYet
                        }
                    )
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

    override fun doCoroutinePuzzleSolveAttempt(
        key: ApiKey,
        puzzleId: String,
        calls: Flow<CoroutinePuzzleEndpointCall>,
    ): Flow<CoroutinePuzzleEndpointAnswer> = channelFlow {
        val puzzle = WorkshopStage
            .entries
            .firstOrNull { it.name == puzzleId }
            ?.let { findCoroutinePuzzleFor(it) }
            ?: run {
                println("Someone tried to request coroutine puzzle id: $puzzleId")
                send(CoroutinePuzzleEndpointAnswer.IncorrectInput)
                return@channelFlow
            }

        send(try {
            puzzle.solve {
                calls.collect { call ->
                    launch {
                        send(CallAnswered(
                            call.callId,
                            deserializeEndpoint(call.endPointName).submitRawCall(call.argument),
                        ))
                    }
                }
            }.let {
                when (it) {
                    is CoroutinePuzzleSolutionResult.Failure -> CoroutinePuzzleEndpointAnswer.Done(it)
                    is CoroutinePuzzleSolutionResult.Success ->
                        when (onEvent.fire(PuzzleFinishedEvent(Clock.System.now(), key, puzzleId))) {
                            PuzzleCompletionResult.AlreadySolved -> CoroutinePuzzleEndpointAnswer.AlreadySolved
                            PuzzleCompletionResult.Done -> CoroutinePuzzleEndpointAnswer.Done(it)
                            PuzzleCompletionResult.PuzzleNotOpenedYet -> CoroutinePuzzleEndpointAnswer.PuzzleNotOpenedYet
                        }
                }
            }
        } catch (_: SerializationException) {
            CoroutinePuzzleEndpointAnswer.IncorrectInput
        })
    }

    override suspend fun setSlider(key: ApiKey, suggestedRatio: Double): SlideResult =
        onEvent.fire(SliderSuggestionEvent(key, suggestedRatio))

    override fun playPressiveGame(key: ApiKey, pressEvents: Flow<PressiveGamePressType>): Flow<String> =
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

    override fun discoGameInstructions(key: ApiKey): Flow<DiscoGameInstruction?> = serverState
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

    override fun discoGameBackground(key: ApiKey): Flow<SerializableColor> = serverState
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

    override fun pressiveGameBackground(key: ApiKey): Flow<SerializableColor?> = serverState
        .map { (it.pressiveGameState as? PressiveGameState.ThirdGameInProgress)?.participantThatIsBeingRung == key }
        .distinctUntilChanged()
        .map { isBeingRung -> SerializableColor(0, 0, 0).takeIf { isBeingRung } }
}

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
    Registration,
    PressiveGameStage,
    DiscoGame,
    SumOfTwoIntsSlow,
    SumOfTwoIntsFast,
    CollectLatest,
    SimpleFlow,
    SliderGameStage -> null
    PalindromeCheckTask -> puzzle(
        "racecar" to true,
        "Racecar" to false,
        "radar" to true,
        "foo" to false,
        "abba" to true,
        "ABBA" to true,
    )
    FindMinimumAgeOfUserTask -> puzzle(
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
    FindOldestUserTask -> puzzle(
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

fun deserializeEndpoint(endpointId: String): CoroutinePuzzleEndPoint<*, *> =
    CoroutinePuzzleEndPoint<Nothing, Nothing>(endpointId)
