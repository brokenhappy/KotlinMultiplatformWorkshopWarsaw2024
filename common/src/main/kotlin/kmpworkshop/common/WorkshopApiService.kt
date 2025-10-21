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
    fun doCoroutinePuzzleSolveAttempt(key: ApiKey, puzzleId: String, calls: Flow<CoroutinePuzzleEndpointCall>): Flow<CoroutinePuzzleEndpointAnswer>
    fun doPuzzleSolveAttempt(key: ApiKey, puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus>
}

fun WorkshopApiService.asServer(apiKey: ApiKey) = object : WorkshopServer {
    override fun currentStage(): Flow<WorkshopStage> = this@asServer.currentStage()
    override fun doPuzzleSolveAttempt(puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus> =
        this@asServer.doPuzzleSolveAttempt(apiKey, puzzleName, answers)

    override suspend fun doCoroutinePuzzleSolveAttempt(
        puzzleId: String,
        callback: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit,
    ): CoroutinePuzzleSolutionResult {
        data class CallInProgress(
            val callId: Int,
            val deferred: CompletableDeferred<JsonElement>,
            val endPoint: CoroutinePuzzleEndPoint<*, *>,
        )
        val callsInProgress = MutableStateFlow<List<CallInProgress>>(emptyList())
        return doCoroutinePuzzleSolveAttempt(
            key = apiKey,
            puzzleId = puzzleId,
            calls = channelFlow {
                val callIdCounter = AtomicInt(0)
                callback(object: CoroutinePuzzleSolutionScope {
                    override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement {
                        val deferred = CompletableDeferred<JsonElement>()
                        val callInProgress = CallInProgress(callIdCounter.fetchAndIncrement(), deferred, this)
                        callsInProgress.update { it + callInProgress }
                        send(CoroutinePuzzleEndpointCall(callInProgress.callId, this.description, t))
                        return deferred.await()
                    }
                }, this)
            },
        ).mapNotNull { answer ->
            when (answer) {
                is CoroutinePuzzleEndpointAnswer.CallAnswered -> {
                    val call = callsInProgress.value.first { it.callId == answer.callId }
                    callsInProgress.update { it - call }
                    answer.answer
                        ?.sideEffect { call.deferred.complete(it) }
                        ?: call.deferred.completeExceptionally(Exception("500: Internal server error... :("))
                    null
                }
                is CoroutinePuzzleEndpointAnswer.Done -> answer.result
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
data class CoroutinePuzzleEndpointCall(
    val callId: Int,
    val endPointName: String, // Should be: CoroutinePuzzleEndpoint<*, *>, but that crashes the compiler :sweat_smile:
    val argument: JsonElement,
)

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
