package kmpworkshop.common

import kmpworkshop.common.CoroutinePuzzleBatchEntry.SubmissionPayload
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

interface WorkshopServer : CoroutinePuzzleProvider, KotlinBasicsPuzzleProvider {
    fun currentStage(): Flow<WorkshopStage>
}

fun interface CoroutinePuzzleProvider {
    fun coroutinePuzzle(stage: CoroutinePuzzleStage): CoroutinePuzzle
}

fun interface KotlinBasicsPuzzleProvider {
    fun kotlinBasicsPuzzle(stage: KotlinBasicsPuzzleStage): KotlinBasicsPuzzle
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

    fun doKotlinBasicsPuzzleSolveAttempt(
        key: ApiKey,
        puzzleId: String,
        answers: Flow<JsonElement>,
    ): Flow<SolvingStatus>
}

fun WorkshopApiService.asServer(
    apiKey: ApiKey,
): WorkshopServer = object : WorkshopServer {
    override fun currentStage(): Flow<WorkshopStage> = this@asServer.currentStage()

    override fun kotlinBasicsPuzzle(stage: KotlinBasicsPuzzleStage): KotlinBasicsPuzzle =
        mapFlowsToCommunicationProtocol { answers ->
            doKotlinBasicsPuzzleSolveAttempt(apiKey, stage.name, answers)
        }.asKotlinBasicsPuzzle()

    override fun coroutinePuzzle(stage: CoroutinePuzzleStage): CoroutinePuzzle =
        mapFlowsToCommunicationProtocol { submissions ->
            doCoroutinePuzzleSolveAttempt(apiKey, stage.name, submissions)
        }.asPuzzle()
}

@Serializable
sealed interface WorkshopStage {
    val kotlinFile: String

    data object Registration : WorkshopStage {
        override val kotlinFile: String = "Registration.kt"
    }
    enum class KotlinBasicsPuzzleStage(override val kotlinFile: String): WorkshopStage {
        PalindromeCheckTask("PalindromeCheck.kt"),
        FindMinimumAgeOfUserTask("MinimumAgeFinding.kt"),
        FindOldestUserTask("OldestUserFinding.kt"),
    }
    enum class CoroutinePuzzleStage(override val kotlinFile: String): WorkshopStage {
        SumOfTwoIntsSlow("NumSumFun.kt"),
        SumOfTwoIntsFast("NumSumFun.kt"),
        FindMaximumAgeCoroutines("MaximumAgeFindingWithCoroutines.kt"),
        FastFindMaximumAgeCoroutines("MaximumAgeFindingWithCoroutines.kt"),
        MappingFromLegacyApisStepOne("MappingFromLegacyApis.kt"),
        MappingFromLegacyApisStepTwo("MappingFromLegacyApis.kt"),
        MappingFromLegacyApisStepThree("MappingFromLegacyApis.kt"),
        MappingFromLegacyApisStepFour("MappingFromLegacyApis.kt"),
        ExceptionCatchingWithCoroutines("ExceptionCatchingWithCoroutines.kt"),
        SimpleFlow("FlowShow.kt"),
        CollectLatest("FlowShow.kt"),
        FileExposureStepOne("FileExposure.kt"),
        FileExposureStepTwo("FileExposure.kt"),
        FileExposureStepThree("FileExposure.kt"),
    }
}

@Serializable
data class SerializableColor(val red: Int, val green: Int, val blue: Int)

@Serializable
sealed class SolvingStatus {
    @Serializable
    data class Next(val questionJson: JsonElement) : SolvingStatus()
    @Serializable
    data class Done(val result: KotlinBasicsPuzzleResult) : SolvingStatus()
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
