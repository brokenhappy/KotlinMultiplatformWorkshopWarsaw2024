package kmpworkshop.server

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kmpworkshop.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.util.*
import javax.sound.sampled.AudioSystem
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

fun main(): Unit = runBlocking {
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
        if (updateServerStateAndGetValue { it to it.participantFor(key) } == null) {
            emit(SolvingStatus.InvalidApiKey)
            return@flow
        }
        val puzzle =
            WorkshopStage
                .entries
                .firstOrNull()
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
                                    puzzleStates = oldState.puzzleStates + (
                                        puzzleName to puzzleState.copy(
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

    override val coroutineContext = coroutineContext
}

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

private fun findPuzzleFor(stage: WorkshopStage): Puzzle<*, *> = when (stage) {
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