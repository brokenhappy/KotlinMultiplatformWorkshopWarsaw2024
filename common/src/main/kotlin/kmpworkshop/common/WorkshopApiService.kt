package kmpworkshop.common

import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
        clientMetadataHash: String,
        messages: Flow<List<WithCallId<CoroutinePuzzleSubmissionPayload>>>
    ): Flow<CoroutinePuzzleExpectationBatchOrCompletion>

    fun doKotlinBasicsPuzzleSolveAttempt(
        key: ApiKey,
        puzzleId: String,
        answers: Flow<JsonElement>,
    ): Flow<SolvingStatus>
}

fun WorkshopApiService.asServer(
    apiKey: ApiKey,
    clientMetadataHash: String = DefaultApis.endpointHash(),
): WorkshopServer = object : WorkshopServer {
    override fun currentStage(): Flow<WorkshopStage> = this@asServer.currentStage()

    override fun kotlinBasicsPuzzle(stage: KotlinBasicsPuzzleStage): KotlinBasicsPuzzle =
        mapFlowsToCommunicationProtocol { answers ->
            doKotlinBasicsPuzzleSolveAttempt(apiKey, stage.name, answers)
        }.asKotlinBasicsPuzzle()

    override fun coroutinePuzzle(stage: CoroutinePuzzleStage): CoroutinePuzzle =
        mapFlowsToCommunicationProtocol { submissions ->
            doCoroutinePuzzleSolveAttempt(apiKey, stage.name, clientMetadataHash, submissions)
        }.asPuzzle()
}

@Serializable(with = WorkshopStageSerializer::class)
sealed interface WorkshopStage {
    val kotlinFile: String

    @Serializable data object Registration : WorkshopStage {
        override val kotlinFile: String = "Registration.kt"
    }

    @Serializable enum class KotlinBasicsPuzzleStage(override val kotlinFile: String): WorkshopStage {
        PalindromeCheckTask("PalindromeCheck.kt"),
        FindMinimumAgeOfUserTask("MinimumAgeFinding.kt"),
        FindOldestUserTask("OldestUserFinding.kt"),
    }

    @Serializable enum class CoroutinePuzzleStage(override val kotlinFile: String): WorkshopStage {
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

/**
 * Keeps enum stages as JSON strings instead of trying to add a polymorphic type discriminator to them.
 *
 * `Json` can only use a discriminator with object-like values, while enum serializers produce primitives.
 * Since the enum name is the only subtype marker in this representation, names must be unique across
 * [KotlinBasicsPuzzleStage] and [CoroutinePuzzleStage]. When adding a stage, rename it if its name is
 * already used by the other enum; `WorkshopStageSerializationTest` enforces this contract.
 */
object WorkshopStageSerializer : JsonContentPolymorphicSerializer<WorkshopStage>(WorkshopStage::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<WorkshopStage> = when (element) {
        is JsonObject -> WorkshopStage.Registration.serializer()
        is JsonPrimitive -> when (element.content) {
            in KotlinBasicsPuzzleStage.entries.map { it.name } -> KotlinBasicsPuzzleStage.serializer()
            in CoroutinePuzzleStage.entries.map { it.name } -> CoroutinePuzzleStage.serializer()
            else -> error("Unknown workshop stage: ${element.content}")
        }
        else -> error("Workshop stage must be a JSON object or string, but was $element")
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
