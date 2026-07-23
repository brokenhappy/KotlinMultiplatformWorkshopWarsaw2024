@file:OptIn(ExperimentalAtomicApi::class)

package kmpworkshop.common

import kmpworkshop.common.CoroutinePuzzleEndpointAnswer.CallAnswered.CallAnswer
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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
            val deferred: CompletableDeferred<JsonElement>,
            val isTaken: AtomicBoolean,
        )
        val callsInProgress = MutableStateFlow<List<CallInProgress>>(emptyList())
        return@coroutineScope doCoroutinePuzzleSolveAttempt(
            key = apiKey,
            puzzleId = puzzleId,
            calls = channelFlow {
                val callIdCounter = AtomicInt(0)
                callback(object: CoroutinePuzzleSolutionScope {
                    override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement {
                        val callInProgress = CallInProgress(
                            callId = callIdCounter.fetchAndIncrement(),
                            deferred = CompletableDeferred(),
                            isTaken = AtomicBoolean(false),
                        )
                        callsInProgress.update { it + callInProgress }
                        try {
                            send(CoroutinePuzzleEndpointCall(callInProgress.callId, this.descriptor, t))
                        } catch (t: Throwable) {
                            callsInProgress.update { it - callInProgress }
                            throw t
                        }
                        return try {
                            callInProgress.deferred.await()
                        } catch (c: CancellationException) {
                            if (callInProgress.isTaken.compareAndSet(expectedValue = false, newValue = true)) {
                                callsInProgress.update { it - callInProgress }
                                importantCleanup {
                                    send(CoroutinePuzzleEndpointCallCancellation(callInProgress.callId))
                                }
                            }
                            throw c
                        }
                    }
                }, this)
            },
        ).mapNotNull { reply ->
            when (reply) {
                is CoroutinePuzzleEndpointAnswer.CallAnswered -> {
                    val answeredCall = callsInProgress.value.firstOrNull { it.callId == reply.callId }
                        ?: return@mapNotNull null
                    if (!answeredCall.isTaken.compareAndSet(expectedValue = false, newValue = true))
                        return@mapNotNull null
                    callsInProgress.updateWithContract { oldCalls -> oldCalls - answeredCall }

                    when (val answer = reply.answer) {
                        is CallAnswer.Success -> answeredCall.deferred.complete(answer.content)
                        CallAnswer.Canceled -> answeredCall.deferred.cancel()
                        CallAnswer.Exceptional ->
                            answeredCall.deferred.completeExceptionally(Exception("500: Internal server error... :("))
                    }
                    null
                }
                is CoroutinePuzzleEndpointAnswer.Done -> reply.result
                CoroutinePuzzleEndpointAnswer.IncorrectInput -> accidentalChangesMadeError()
                CoroutinePuzzleEndpointAnswer.AlreadySolved ->
                    CoroutinePuzzleSolutionResult.Failure(emptyList(), CoroutinePuzzleSolutionResult.Failure.Reason.Custom("You have already solved this puzzle!"))
                CoroutinePuzzleEndpointAnswer.PuzzleNotOpenedYet ->
                    CoroutinePuzzleSolutionResult.Failure(emptyList(), CoroutinePuzzleSolutionResult.Failure.Reason.Custom("The puzzle has not been opened yet!"))
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
    MappingFromLegacyApisStepFour("MappingFromLegacyApisStepOne.kt"),
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
    data class CoroutinePuzzleEndpointCallCancellation(val callId: Int) : CoroutinePuzzleEndpointCallOrConfirmation()
}

@Serializable
sealed class CoroutinePuzzleEndpointAnswer {
    @Serializable
    data class CallAnswered(
        val callId: Int,
        /** null implies 500 internal server error */
        val answer: CallAnswer,
    ) : CoroutinePuzzleEndpointAnswer() {
        @Serializable
        sealed class CallAnswer {
            @Serializable
            data class Success(val content: JsonElement) : CallAnswer()
            @Serializable
            data object Exceptional : CallAnswer()
            @Serializable
            data object Canceled : CallAnswer()
        }
    }
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
data class ApiKey(val stringRepresentation: String)

// We don't want to burden the user with @Serializable, so we hide it here
@Serializable
data class SerializableUser(val name: String, val age: Int) {
    override fun toString(): String = "User(name=$name, age=$age)"
}

fun accidentalChangesMadeError(): Nothing =
    error("You accidentally made changes to the puzzle types or scaffolding.\nPlease revert those changes yourself or ask the workshop host for help!")
