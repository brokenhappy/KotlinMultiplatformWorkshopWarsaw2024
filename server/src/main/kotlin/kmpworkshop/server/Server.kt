@file:Suppress("ReplaceToWithInfixForm")
@file:OptIn(ExperimentalTime::class)

package kmpworkshop.server

import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleEndpointAnswer.CallAnswered
import kmpworkshop.common.CoroutinePuzzleEndpointCallOrConfirmation.CoroutinePuzzleEndpointCall
import kmpworkshop.common.CoroutinePuzzleEndpointCallOrConfirmation.CoroutinePuzzleEndpointCallCancellation
import kmpworkshop.common.CoroutinePuzzleEndpointCallOrConfirmation.CoroutinePuzzleEndpointConfirmation
import kmpworkshop.common.WorkshopStage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

suspend fun main() {
    hostServer()
}

suspend fun hostServer(): Nothing = withContext(Dispatchers.Default) {
    val serverState = MutableStateFlow(ServerState())
    val eventBus = Channel<ScheduledWorkshopEvent>(capacity = Channel.UNLIMITED)
    val soundEvents = MutableSharedFlow<SoundPlayEvent>()
    val onSoundEvent: (SoundPlayEvent) -> Unit = {
        launch { soundEvents.emit(it) }
    }
    launch {
        serve(
            rpcService { workshopService(serverState, onEvent = { eventBus.trySend(it) }) },
            rpcService { adminAccess(serverState, onEvent = { eventBus.trySend(it) }, soundEvents) },
        )
    }
    coroutineScope {
        mainEventLoopWithCommittedStateChannelWritingTo(
            serverState,
            eventBus,
            onSoundEvent = onSoundEvent,
            onEvent = { launch { eventBus.send(it) } },
        ) { initialState, channel ->
            withBackupLoop(initialState, channel, onSoundEvent) { backupRequests, trailingBackup ->
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

fun workshopService(
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
            .firstOrNull { it.name == puzzleName }
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
        calls: Flow<CoroutinePuzzleEndpointCallOrConfirmation>,
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

        val completionHooks = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        val jobs = ConcurrentHashMap<Int, Job>()
        send(try {
            puzzle.solve {
                calls.collect { callOrConfirmation ->
                    when (callOrConfirmation) {
                        is CoroutinePuzzleEndpointCall -> jobs[callOrConfirmation.callId] = launch {
                            try {
                                val (answer, completionHook) = callOrConfirmation
                                    .descriptor
                                    .toEndpoint()
                                    .submitRawCall(callOrConfirmation.argument)
                                completionHooks[callOrConfirmation.callId] = completionHook
                                send(CallAnswered(
                                    callId = callOrConfirmation.callId,
                                    answer = answer,
                                ))
                            } catch (e: CoroutinePuzzleFailedControlFlowException) {
                                throw e
                            } catch (e: Throwable) {
                                if (e !is CancellationException) e.printStackTrace()
                                send(CallAnswered(callOrConfirmation.callId, null)) // Internal server error! Oops!
                            } finally {
                                jobs.remove(callOrConfirmation.callId)
                            }
                        }
                        is CoroutinePuzzleEndpointConfirmation -> {
                            completionHooks.remove(callOrConfirmation.callId)?.complete(Unit)
                        }
                        is CoroutinePuzzleEndpointCallCancellation -> jobs[callOrConfirmation.callId]?.cancel()
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
    SumOfTwoIntsSlow,
    SumOfTwoIntsFast,
    CollectLatest,
    SimpleFlow,
    FindMaximumAgeCoroutines,
    FastFindMaximumAgeCoroutines,
    MappingFromLegacyApisStepOne,
    MappingFromLegacyApisStepTwo,
    MappingFromLegacyApisStepThree,
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
