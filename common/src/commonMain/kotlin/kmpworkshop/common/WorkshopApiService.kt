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
    suspend fun setSlider(suggestedRatio: Double): SlideResult
    fun playPressiveGame(pressEvents: Flow<PressiveGamePressType>): Flow<String>
    fun pressiveGameBackground(): Flow<SerializableColor?>
    fun discoGameInstructions(): Flow<DiscoGameInstruction?>
    fun discoGameBackground(): Flow<SerializableColor>
    suspend fun discoGamePress()
}

@Rpc interface WorkshopApiService {
    suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult
    suspend fun verifyRegistration(key: ApiKey): NameVerificationResult
    fun currentStage(): Flow<WorkshopStage>
    fun doCoroutinePuzzleSolveAttempt(key: ApiKey, puzzleId: String, calls: Flow<CoroutinePuzzleEndpointCall>): Flow<CoroutinePuzzleEndpointAnswer>
    fun doPuzzleSolveAttempt(key: ApiKey, puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus>
    suspend fun setSlider(key: ApiKey, suggestedRatio: Double): SlideResult
    fun playPressiveGame(key: ApiKey, pressEvents: Flow<PressiveGamePressType>): Flow<String>
    fun pressiveGameBackground(key: ApiKey): Flow<SerializableColor?>
    fun discoGameInstructions(key: ApiKey): Flow<DiscoGameInstruction?>
    fun discoGameBackground(key: ApiKey): Flow<SerializableColor>
    suspend fun discoGamePress(key: ApiKey)
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
                    call.deferred.complete(answer.answer)
                    null
                }
                is CoroutinePuzzleEndpointAnswer.Done -> answer.result
                CoroutinePuzzleEndpointAnswer.IncorrectInput -> accidentalChangesMadeError()
                CoroutinePuzzleEndpointAnswer.AlreadySolved -> TODO()
                CoroutinePuzzleEndpointAnswer.PuzzleNotOpenedYet -> TODO()
            }
        }.first()
    }

    override suspend fun setSlider(suggestedRatio: Double): SlideResult =
        this@asServer.setSlider(apiKey, suggestedRatio)
    override fun playPressiveGame(pressEvents: Flow<PressiveGamePressType>): Flow<String> =
        this@asServer.playPressiveGame(apiKey, pressEvents)
    override fun pressiveGameBackground(): Flow<SerializableColor?> = this@asServer.pressiveGameBackground(apiKey)
    override fun discoGameInstructions(): Flow<DiscoGameInstruction?> = this@asServer.discoGameInstructions(apiKey)
    override fun discoGameBackground(): Flow<SerializableColor> = this@asServer.discoGameBackground(apiKey)
    override suspend fun discoGamePress() {
        this@asServer.discoGamePress(apiKey)
    }
}

@Serializable
enum class WorkshopStage(val kotlinFile: String) {
    Registration("Registration.kt"),
    PalindromeCheckTask("PalindromeCheck.kt"),
    FindMinimumAgeOfUserTask("MinimumAgeFinding.kt"),
    FindOldestUserTask("OldestUserFinding.kt"),
    SumOfTwoIntsSlow("NumSumYum.kt"),
    SumOfTwoIntsFast("NumSumYum.kt"),
    SimpleFlow("FlowsAndCollecting.kt"),
    CollectLatest("FlowsAndCollecting.kt"),
    // TODO: Handle deletion of user!
    // TODO: Test scroll ability with 30 users!
    SliderGameStage("SliderGameClient.kt"),
    // TODO: Handle deletion of user!
    PressiveGameStage("PressiveGameClient.kt"),
    DiscoGame("DiscoGameClient.kt"),
}

@Serializable
data class SerializableColor(val red: Int, val green: Int, val blue: Int)

@Serializable
enum class PressiveGamePressType {
    SinglePress, DoublePress, LongPress;
}

@Serializable
enum class DiscoGameInstruction(val char: Char, val dx: Int, val dy: Int) {
    Left('←', -1, 0),
    LeftUp('↖', -1, -1),
    Up('↑', 0, -1),
    RightUp('↗', 1, -1),
    Right('→', 1, 0),
    RightDown('↘', 1, 1),
    Down('↓', 0, 1),
    LeftDown('↙', -1, 1),
}

@Serializable
sealed class SlideResult {
    @Serializable
    data class Success(val setRatio: Double) : SlideResult()
    @Serializable
    data object NoSliderGameInProgress : SlideResult()
    @Serializable
    data object InvalidApiKey : SlideResult()
}

@Serializable
data class CoroutinePuzzleEndpointCall(
    val callId: Int,
    val endPointName: String, // Should be: CoroutinePuzzleEndpoint<*, *>, but that crashes the compiler :sweat_smile:
    val argument: JsonElement,
)

@Serializable
sealed class CoroutinePuzzleEndpointAnswer {
    @Serializable
    data class CallAnswered(val callId: Int, val answer: JsonElement) : CoroutinePuzzleEndpointAnswer()
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
