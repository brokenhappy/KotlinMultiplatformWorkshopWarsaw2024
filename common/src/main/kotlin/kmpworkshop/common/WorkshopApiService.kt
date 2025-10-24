@file:OptIn(ExperimentalAtomicApi::class)

package kmpworkshop.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kmpworkshop.common.CoroutinePuzzleEndpointCallOrConfirmation.CoroutinePuzzleEndpointCall
import kmpworkshop.common.CoroutinePuzzleEndpointCallOrConfirmation.CoroutinePuzzleEndpointCallCancellation
import kmpworkshop.common.CoroutinePuzzleEndpointCallOrConfirmation.CoroutinePuzzleEndpointConfirmation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

interface WorkshopServer {
    fun currentStage(): Flow<WorkshopStage>
    fun doPuzzleSolveAttempt(puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus>
    suspend fun doCoroutinePuzzleSolveAttempt(
        puzzleId: String,
        callback: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit,
    ): CoroutinePuzzleSolutionResult
}

@Rpc interface WorkshopApiService {
    suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult
    suspend fun verifyRegistration(key: ApiKey): NameVerificationResult
    fun currentStage(): Flow<WorkshopStage>
    fun doCoroutinePuzzleSolveAttempt(key: ApiKey, puzzleId: String, calls: Flow<CoroutinePuzzleEndpointCallOrConfirmation>): Flow<CoroutinePuzzleEndpointAnswer>
    fun doPuzzleSolveAttempt(key: ApiKey, puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus>
}

fun WorkshopApiService.asServer(apiKey: ApiKey) = object : WorkshopServer {
    override fun currentStage(): Flow<WorkshopStage> = this@asServer.currentStage()
    override fun doPuzzleSolveAttempt(puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus> =
        this@asServer.doPuzzleSolveAttempt(apiKey, puzzleName, answers)

    override suspend fun doCoroutinePuzzleSolveAttempt(
        puzzleId: String,
        callback: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit,
    ): CoroutinePuzzleSolutionResult = coroutineScope {
        data class CallInProgress(
            val callId: Int,
            val deferred: CompletableDeferred<Pair<JsonElement, CompletableDeferred<Unit>>>,
            val endPoint: CoroutinePuzzleEndPoint<*, *>,
            val isTaken: AtomicBoolean,
        )
        val confirmations = Channel<CoroutinePuzzleEndpointConfirmation>()
        val callsInProgress = MutableStateFlow<List<CallInProgress>>(emptyList())
        val confirmationsToAwait = MutableStateFlow(0)
        return@coroutineScope doCoroutinePuzzleSolveAttempt(
            key = apiKey,
            puzzleId = puzzleId,
            calls = channelFlow {
                val callIdCounter = AtomicInt(0)
                launch {
                    for (confirmation in confirmations) {
                        send(confirmation)
                    }
                }
                callback(object: CoroutinePuzzleSolutionScope {
                    override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): SubmissionAnswerWithConfirmation {
                        val callInProgress = CallInProgress(
                            callId = callIdCounter.fetchAndIncrement(),
                            deferred = CompletableDeferred(),
                            endPoint = this,
                            isTaken = AtomicBoolean(false),
                        )
                        callsInProgress.update { it + callInProgress }
                        try {
                            send(CoroutinePuzzleEndpointCall(callInProgress.callId, this.descriptor, t))
                        } catch (t: Throwable) {
                            callsInProgress.update { it - callInProgress }
                            throw t
                        }
                        confirmationsToAwait.update { it + 1 }
                        val (answer, completionHook) = try {
                            callInProgress.deferred.await()
                        } catch (c: CancellationException) {
                            if (callInProgress.isTaken.compareAndSet(expectedValue = false, newValue = true)) {
                                callsInProgress.update { it - callInProgress }
                                confirmationsToAwait.update { it - 1 }
                                importantCleanup {
                                    send(CoroutinePuzzleEndpointCallCancellation(callInProgress.callId))
                                }
                            }
                            throw c
                        }
                        return completionHook.completeAfter {
                            SubmissionAnswerWithConfirmation(
                                answer,
                                CompletableDeferred<Unit>().apply {
                                    completeExceptionally(IllegalStateException("Should never be awaited"))
                                },
                            )
                        }
                    }
                }, this)
                confirmationsToAwait.first { it == 0 } // Wait util confirmations are sent
                confirmations.close()
            },
        ).mapNotNull { reply ->
            when (reply) {
                is CoroutinePuzzleEndpointAnswer.CallAnswered -> {
                    val answeredCall = callsInProgress.value.firstOrNull { it.callId == reply.callId }
                        ?: return@mapNotNull null
                    if (!answeredCall.isTaken.compareAndSet(expectedValue = false, newValue = true))
                        return@mapNotNull null
                    callsInProgress.updateWithContract { oldCalls -> oldCalls - answeredCall }
                    reply.answer?.sideEffect { answer ->
                        val completionHook = CompletableDeferred<Unit>()
                        answeredCall.deferred.complete(answer to completionHook)
                        this@coroutineScope.launch {
                            completionHook.await()

                            confirmations.send(CoroutinePuzzleEndpointConfirmation(answeredCall.callId))
                            confirmationsToAwait.update { it - 1 }
                        }
                    } ?: answeredCall.deferred.completeExceptionally(Exception("500: Internal server error... :("))
                    null
                }
                is CoroutinePuzzleEndpointAnswer.Done -> reply.result
                CoroutinePuzzleEndpointAnswer.IncorrectInput -> accidentalChangesMadeError()
                CoroutinePuzzleEndpointAnswer.AlreadySolved ->
                    CoroutinePuzzleSolutionResult.Failure("You have already solved this puzzle!")
                CoroutinePuzzleEndpointAnswer.PuzzleNotOpenedYet ->
                    CoroutinePuzzleSolutionResult.Failure("The puzzle has not been opened yet!")
            }
        }.first()
    }
}

@Serializable
enum class WorkshopStage(val kotlinFile: String) {
    Registration("Registration.kt"),
    PalindromeCheckTask("PalindromeCheck.kt"),
    FindMinimumAgeOfUserTask("MinimumAgeFinding.kt"),
    FindOldestUserTask("OldestUserFinding.kt"),
    SumOfTwoIntsSlow("NumSumFun.kt"),
    SumOfTwoIntsFast("NumSumFun.kt"),
    FindMaximumAgeCoroutines("MaximumAgeFindingWithCoroutines.kt"),
    FastFindMaximumAgeCoroutines("MaximumAgeFindingWithCoroutines.kt"),
    MappingFromLegacyApisStepOne("MappingFromLegacyApisStepOne.kt"),
    MappingFromLegacyApisStepTwo("MappingFromLegacyApisStepOne.kt"),
    MappingFromLegacyApisStepThree("MappingFromLegacyApisStepOne.kt"),
    SimpleFlow("FlowShow.kt"),
    CollectLatest("FlowShow.kt"),
}

@Serializable
data class SerializableColor(val red: Int, val green: Int, val blue: Int)

@Serializable
sealed class CoroutinePuzzleEndpointCallOrConfirmation {
    @Serializable
    data class CoroutinePuzzleEndpointCall(
        val callId: Int,
        val descriptor: CoroutinePuzzleEndPointDescriptor, // Should be: CoroutinePuzzleEndpoint<*, *>, but that crashes the kotlinx compiler :sweat_smile:
        val argument: JsonElement,
    ) : CoroutinePuzzleEndpointCallOrConfirmation()
    @Serializable
    data class CoroutinePuzzleEndpointConfirmation(val callId: Int) : CoroutinePuzzleEndpointCallOrConfirmation()
    @Serializable
    data class CoroutinePuzzleEndpointCallCancellation(val callId: Int) : CoroutinePuzzleEndpointCallOrConfirmation()
}

@Serializable
sealed class CoroutinePuzzleEndpointAnswer {
    @Serializable
    data class CallAnswered(
        val callId: Int,
        /** null implies 500 internal server error */
        val answer: JsonElement?,
    ) : CoroutinePuzzleEndpointAnswer()
    @Serializable
    data class Done(val result: CoroutinePuzzleSolutionResult) : CoroutinePuzzleEndpointAnswer()
    @Serializable
    data object IncorrectInput : CoroutinePuzzleEndpointAnswer()
    @Serializable
    data object PuzzleNotOpenedYet : CoroutinePuzzleEndpointAnswer()
    @Serializable
    data object AlreadySolved : CoroutinePuzzleEndpointAnswer()
}

@Serializable
sealed class SolvingStatus {
    @Serializable
    data class Next(val questionJson: JsonElement) : SolvingStatus()
    @Serializable
    data class Failed(val input: JsonElement, val actual: JsonElement, val expected: JsonElement) : SolvingStatus()
    @Serializable
    data object IncorrectInput : SolvingStatus()
    @Serializable
    data object InvalidApiKey : SolvingStatus()
    @Serializable
    data object PuzzleNotOpenedYet : SolvingStatus()
    @Serializable
    data object AlreadySolved : SolvingStatus()
    @Serializable
    data object Done : SolvingStatus()
}

@Serializable
sealed class PuzzleCompletionResult {
    @Serializable
    data object PuzzleNotOpenedYet : PuzzleCompletionResult()
    @Serializable
    data object AlreadySolved : PuzzleCompletionResult()
    @Serializable
    data object Done : PuzzleCompletionResult()
}

@Serializable
sealed class ApiKeyRegistrationResult {
    @Serializable
    data class Success(val key: ApiKey) : ApiKeyRegistrationResult()
    @Serializable
    data object NameAlreadyExists : ApiKeyRegistrationResult()
    @Serializable
    data object NameTooComplex : ApiKeyRegistrationResult()
}

@Serializable
sealed class NameVerificationResult {
    @Serializable
    data object Success : NameVerificationResult()
    @Serializable
    data object ApiKeyDoesNotExist : NameVerificationResult()
    @Serializable
    data object AlreadyRegistered : NameVerificationResult()
    @Serializable
    data object NameAlreadyExists : NameVerificationResult()
}

@Serializable
sealed class NextNumberResult {
    @Serializable
    data class Success(val number: Int) : NextNumberResult()
    @Serializable
    data object TooSlow : NextNumberResult()
    @Serializable
    data object AskedForTooManyNumbers : NextNumberResult()
    @Serializable
    data object NotCurrentlyActiveStage : NextNumberResult()
}

@Serializable
sealed class SumSubmissionResult {
    @Serializable
    data object Success : SumSubmissionResult()
    @Serializable
    data object NotOpenedYet : SumSubmissionResult()
    @Serializable
    data object AlreadySolved : SumSubmissionResult()
    @Serializable
    data class WrongSum(val expected: Int) : SumSubmissionResult()
    @Serializable
    data object InvalidApiKey : SumSubmissionResult()
    @Serializable
    data object TooSlow : SumSubmissionResult()
    @Serializable
    data object NotCurrentlyActiveStage : SumSubmissionResult()
}

@Serializable
data class ApiKey(val stringRepresentation: String)

// We don't want to burden the user with @Serializable, so we hide it here
@Serializable
data class SerializableUser(val name: String, val age: Int) {
    override fun toString(): String = "User(name=$name, age=$age)"
}

fun accidentalChangesMadeError(): Nothing =
    error("You accidentally made changes to the puzzle types or scaffolding.\nPlease revert those changes yourself or ask the workshop host for help!")
