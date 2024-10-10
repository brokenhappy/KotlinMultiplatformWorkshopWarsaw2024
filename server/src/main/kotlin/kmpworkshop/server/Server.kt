package kmpworkshop.server

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kmpworkshop.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.io.File
import java.util.*
import javax.sound.sampled.AudioSystem
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

fun main(): Unit = runBlocking {
    launch(Dispatchers.Default) {
        serverStateProperty.persisting(File(getEnvironment()!!["server-database-file"]!!))
    }
    launch(Dispatchers.Default) {
        serveSingleService<WorkshopService> { coroutineContext ->
            workshopService(coroutineContext)
        }
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "KMP Workshop") {
            MaterialTheme {
                ServerUi()
            }
        }
    }
}

private fun workshopService(coroutineContext: CoroutineContext): WorkshopService = object : WorkshopService {
    override suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult =
        updateServerStateAndGetValue { oldState ->
            when {
                !"[A-z 0-9]{1,20}".toRegex().matches(name) -> oldState to ApiKeyRegistrationResult.NameTooComplex
                oldState.participants.any { it.name == name } -> oldState to ApiKeyRegistrationResult.NameAlreadyExists
                else -> UUID.randomUUID().toString()
                    .let { Participant(name, ApiKey(it)) }
                    .let {
                        oldState.copy(unverifiedParticipants = oldState.unverifiedParticipants + it) to ApiKeyRegistrationResult.Success(
                            it.apiKey
                        )
                    }
            }
        }

    override suspend fun verifyRegistration(key: ApiKey): NameVerificationResult =
        updateServerStateAndGetValue { oldState ->
            val name = oldState
                .unverifiedParticipants
                .firstOrNull { it.apiKey == key }
                ?.name
                ?: return@updateServerStateAndGetValue oldState to NameVerificationResult.ApiKeyDoesNotExist
            val stateWithoutUnverifiedParticipant = oldState.copy(
                unverifiedParticipants = oldState.unverifiedParticipants.filter { it.apiKey != key },
            )
            if (oldState.participants.any { it.name == name })
                return@updateServerStateAndGetValue stateWithoutUnverifiedParticipant to NameVerificationResult.NameAlreadyExists
            stateWithoutUnverifiedParticipant.copy(
                participants = stateWithoutUnverifiedParticipant.participants + Participant(name, key)
            ).to(NameVerificationResult.Success)
                .also { launch(Dispatchers.IO) { playSuccessSound() } }
        }

    override suspend fun doPuzzleSolveAttempt(
        key: ApiKey,
        puzzleName: String,
        answers: Flow<JsonElement>,
    ): Flow<SolvingStatus> = flow {
        if (serverStateProperty.value.participantFor(key) == null) {
            emit(SolvingStatus.InvalidApiKey)
            return@flow
        }
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
            (answers as Flow<JsonElement?>).onStart { emit(null) }.collect { answer ->
                if (answer != null) {
                    val expected = puzzle.getPuzzleOutputAsJsonElementAtIndex(puzzleIndex)
                    if (answer != expected) {
                        emit(SolvingStatus.Failed(lastInput!!, answer, expected))
                        return@collect
                    }
                    puzzleIndex++
                }
                if (puzzleIndex > puzzle.inAndOutputs.lastIndex) {
                    updateServerStateAndGetValue { oldState ->
                        (oldState.puzzleStates[puzzleName] as? PuzzleState.Opened)?.let { puzzleState ->
                            when {
                                key.stringRepresentation in puzzleState.submissions -> oldState to SolvingStatus.AlreadySolved
                                else -> oldState.copy(
                                    puzzleStates = oldState.puzzleStates + puzzleName.to(
                                        puzzleState.copy(
                                            submissions = puzzleState.submissions + (key.stringRepresentation to Clock.System.now())
                                        )
                                    )
                                ).to(SolvingStatus.Done).also { launch(Dispatchers.IO) { playSuccessSound() } }
                            }
                        } ?: (oldState to SolvingStatus.PuzzleNotOpenedYet)
                    }.also { emit(it) }
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
        updateServerStateAndGetValue { oldState ->
            if (oldState.participantFor(key) == null) oldState to SlideResult.InvalidApiKey
            else {
                (oldState.sliderGameState as? SliderGameState.InProgress)?.let { oldGameState ->
                    oldGameState.moveSlider(key, suggestedRatio).withGravityApplied()
                        .let { oldState.copy(sliderGameState = it) to SlideResult.Success(when (it) {
                            is SliderGameState.InProgress -> it.findPositionOfParticipant(key)
                            is SliderGameState.Done -> it
                                .lastState
                                .findPositionOfParticipant(key)
                                .also { launch { playSuccessSound() }}
                            is SliderGameState.NotStarted -> error("Impossible")
                        }) }
                } ?: oldState.to(SlideResult.NoSliderGameInProgress)
            }
        }

    override suspend fun playPressiveGame(key: ApiKey, pressEvents: Flow<PressiveGamePressType>): Flow<String> =
        channelFlow {
            if (serverStateProperty.value.participantFor(key) == null) {
                send("You have not been registered! Contact the workshop host for help!")
                return@channelFlow
            }

            launch {
                pressEvents.collect { pressEvent ->
                    updateServerState {
                        it.copy(pressiveGameState = it.pressiveGameState.pressing(pressEvent, presserKey = key, ::trySend))
                    }
                }
            }

            serverState()
                .map { it.pressiveGameState }
                .map { gameState ->
                    when (gameState) {
                        PressiveGameState.NotStarted -> "The Pressive game has not started yet! Please wait for the workshop host to start it."
                        PressiveGameState.FirstGameDone -> "Waiting for the second game to start!"
                        is PressiveGameState.FirstGameInProgress -> gameState.states[key.stringRepresentation]?.toHint()
                            ?: "I'm so sorry! You have not been included in this game somehow :((. Please contact the workshop host!"
                        PressiveGameState.SecondGameDone -> "The game has finished! Thank you for playing!"
                        is PressiveGameState.SecondGameInProgress -> gameState.states[key.stringRepresentation]?.toHint()
                            ?: "I'm so sorry! You have not been included in this game somehow :((. Please contact the workshop host!"
                    }
                }
                .distinctUntilChanged()
                .collect { send(it) }
        }

    override val coroutineContext = coroutineContext
}

private fun SliderGameState.InProgress.withGravityApplied(): SliderGameState =
    participantStates.values.elementAtOrNull(pegLevel + 1)?.let { slider ->
        if (slider.letsThroughPegPositionedAt(pegPosition)) copy(pegLevel = pegLevel + 1).withGravityApplied()
        else this
    } ?: SliderGameState.Done(copy(pegLevel = participantStates.size))

private fun SliderGameState.InProgress.findLevelOfParticipant(key: ApiKey): Int =
    participantStates.entries.indexOfFirst { it.key == key.stringRepresentation }

private fun SliderGameState.InProgress.findPositionOfParticipant(key: ApiKey): Double =
    participantStates.entries.first { it.key == key.stringRepresentation }.value.position

private fun SliderGameState.InProgress.moveSlider(key: ApiKey, ratio: Double): SliderGameState.InProgress {
    val sliderState = participantStates[key.stringRepresentation]!!
    return copy(
        participantStates = (participantStates + key.stringRepresentation.to(
            sliderState.copy(
                position = if (findLevelOfParticipant(key) == pegLevel) {
                    ratio.coerceIn(sliderState.positionRangeInWhichPegWouldFallThrough(pegPosition))
                } else ratio
            )
        )).toSortedMap()
    )
}

internal const val PegWidth = 0.075
internal const val SliderGapWidth = 0.1

private fun <T, R> Puzzle<T, R>.getPuzzleInputAsJsonElementAtIndex(puzzleIndex: Int): JsonElement =
    Json.encodeToJsonElement(tSerializer, inAndOutputs[puzzleIndex].first)

private fun <T, R> Puzzle<T, R>.getPuzzleOutputAsJsonElementAtIndex(puzzleIndex: Int): JsonElement =
    Json.encodeToJsonElement(rSerializer, inAndOutputs[puzzleIndex].second)

private suspend fun playSuccessSound() {
    AudioSystem.getClip().use { clip ->
        clip.open(AudioSystem.getAudioInputStream((object {})::class.java.getResourceAsStream("/success.wav")))
        clip.start()
        clip.drain()
        delay(3.seconds)
    }
}

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

private fun ServerState.participantFor(apiKey: ApiKey) = participants.firstOrNull { it.apiKey == apiKey }

private var serverStateProperty = MutableStateFlow(ServerState())

internal fun serverState(): Flow<ServerState> = serverStateProperty
internal inline fun <T : Any> updateServerStateAndGetValue(update: (ServerState) -> Pair<ServerState, T>): T {
    var result: T? = null
    serverStateProperty.update {
        val (newState, value) = update(it)
        result = value
        newState
    }
    return result!!
}

internal inline fun updateServerState(update: (ServerState) -> ServerState) {
    updateServerStateAndGetValue { update(it) to Unit }
}
