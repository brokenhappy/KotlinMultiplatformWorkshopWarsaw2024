package kmpworkshop.common

import kmpworkshop.common.CoroutinePuzzleBatchEntry.SubmissionPayload
import kotlinx.coroutines.flow.*
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

interface WorkshopServer : CoroutinePuzzleProvider {
    fun currentStage(): Flow<WorkshopStage>
    fun doPuzzleSolveAttempt(puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus>
}

fun interface CoroutinePuzzleProvider {
    fun coroutinePuzzle(stage: WorkshopStage): CoroutinePuzzle
}

@Rpc interface WorkshopApiService {
    suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult
    suspend fun verifyRegistration(key: ApiKey): NameVerificationResult
    fun currentStage(): Flow<WorkshopStage>
    fun doCoroutinePuzzleSolveAttempt(
        key: ApiKey,
        puzzleId: String,
        messages: Flow<List<CoroutinePuzzleBatchEntry<SubmissionPayload>>>
    ): Flow<CoroutinePuzzleExpectationBatchOrCompletion>

    fun doPuzzleSolveAttempt(key: ApiKey, puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus>
}

fun WorkshopApiService.asServer(
    apiKey: ApiKey,
): WorkshopServer = object : WorkshopServer {
    override fun currentStage(): Flow<WorkshopStage> = this@asServer.currentStage()
    override fun doPuzzleSolveAttempt(puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus> =
        this@asServer.doPuzzleSolveAttempt(apiKey, puzzleName, answers)

    override fun coroutinePuzzle(stage: WorkshopStage): CoroutinePuzzle =
        coroutinePuzzleCommunicationChannel { incoming, outgoing ->
            try {
                doCoroutinePuzzleSolveAttempt(apiKey, stage.name, outgoing.consumeAsFlow())
                    .collect { incoming.send(it) }
            } finally {
                incoming.close()
            }
        }.asPuzzle()
}

@Serializable
enum class WorkshopStage(val kotlinFile: String, val isCoroutinePuzzle: Boolean = true) {
    Registration("Registration.kt", isCoroutinePuzzle = false),
    PalindromeCheckTask("PalindromeCheck.kt", isCoroutinePuzzle = false),
    FindMinimumAgeOfUserTask("MinimumAgeFinding.kt", isCoroutinePuzzle = false),
    FindOldestUserTask("OldestUserFinding.kt", isCoroutinePuzzle = false),
    SumOfTwoIntsSlow("NumSumFun.kt"),
    SumOfTwoIntsFast("NumSumFun.kt"),
    FindMaximumAgeCoroutines("MaximumAgeFindingWithCoroutines.kt"),
    FastFindMaximumAgeCoroutines("MaximumAgeFindingWithCoroutines.kt"),
    MappingFromLegacyApisStepOne("MappingFromLegacyApisStepOne.kt"),
    MappingFromLegacyApisStepTwo("MappingFromLegacyApisStepOne.kt"),
    MappingFromLegacyApisStepThree("MappingFromLegacyApisStepOne.kt"),
    MappingFromLegacyApisStepFour("MappingFromLegacyApisStepOne.kt"),
    ExceptionCatchingWithCoroutines("ExceptionCatchingWithCoroutines.kt"),
    SimpleFlow("FlowShow.kt"),
    CollectLatest("FlowShow.kt"),
    FileExposureStepOne("FileExposureScaffolding.kt"),
    FileExposureStepTwo("FileExposureScaffolding.kt"),
    FileExposureStepThree("FileExposureScaffolding.kt"),
}

@Serializable
data class SerializableColor(val red: Int, val green: Int, val blue: Int)

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

const val accidentalChangesMadeMessage =
    "You accidentally made changes to the puzzle types or scaffolding.\nPlease revert those changes yourself or ask the workshop host for help!"

fun accidentalChangesMadeError(): Nothing = error(accidentalChangesMadeMessage)
