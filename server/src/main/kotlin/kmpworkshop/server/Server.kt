package kmpworkshop.server

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kmpworkshop.common.ApiKey
import kmpworkshop.common.ApiKeyRegistrationResult
import kmpworkshop.common.NameVerificationResult
import kmpworkshop.common.SolvingStatus
import kmpworkshop.common.WorkshopService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.util.*
import kotlin.coroutines.CoroutineContext

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
            ) to NameVerificationResult.Success
        }

    override suspend fun doPuzzleSolveAttempt(
        key: ApiKey,
        puzzleName: String,
        answers: Flow<JsonElement>,
    ): Flow<SolvingStatus> = flow {
        val puzzle = puzzles.firstOrNull { it.name == puzzleName } ?: run {
            println("Someone tried to request puzzle name: $puzzleName")
            emit(SolvingStatus.IncorrectInput)
            return@flow
        }

        var puzzleIndex = 0
        var lastInput: JsonElement? = null
        try {
            (answers as Flow<JsonElement?>).onStart { emit(null) }.collect { answerJson ->
                if (answerJson != null) {
                    val answer = Json.decodeFromJsonElement(puzzle.rSerializer, answerJson)
                    val (_, expected) = puzzle.inAndOutputs[puzzleIndex]
                    if (answer != expected) {
                        emit(SolvingStatus.Failed(lastInput!!, answerJson, Json.encodeToJsonElement(puzzle.rSerializer, expected)))
                        return@collect
                    }
                    puzzleIndex++
                }
                if (puzzleIndex > puzzle.inAndOutputs.lastIndex) {
                    emit(SolvingStatus.Done)
                } else {
                    val (input, _) = puzzle.inAndOutputs[puzzleIndex]
                    emit(SolvingStatus.Next(Json.encodeToJsonElement(puzzle.tSerializer, input).also { lastInput = it }))
                }
            }
        } catch (t: SerializationException) {
            emit(SolvingStatus.IncorrectInput)
        }
    }

    override val coroutineContext = coroutineContext
}

private inline fun <reified T, reified R> puzzle(name: String, vararg inAndOutputs: Pair<T, R>): Puzzle<T, R> =
    Puzzle(name, inAndOutputs.asList(), serializer(), serializer())

private data class Puzzle<T, R>(
    val name: String,
    val inAndOutputs: List<Pair<T, R>>,
    val tSerializer: KSerializer<T>,
    val rSerializer: KSerializer<R>,
)

private val puzzles = listOf(
    puzzle(
        "PalindromeCheck.kt",
        "racecar" to true,
        "Racecar" to false,
        "radar" to true,
        "foo" to false,
        "abba" to true,
        "ABBA" to true,
    ),
)

private fun ServerState.participantFor(apiKey: ApiKey) = participants.firstOrNull { it.apiKey == apiKey }