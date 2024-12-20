package kmpworkshop.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.rpc.RPC
import kotlinx.rpc.streamScoped
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

interface WorkshopApiService : RPC {
    suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult
    suspend fun verifyRegistration(key: ApiKey): NameVerificationResult
    suspend fun currentStage(): Flow<WorkshopStage>
    suspend fun doPuzzleSolveAttempt(key: ApiKey, puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus>
    suspend fun setSlider(key: ApiKey, suggestedRatio: Double): SlideResult
    suspend fun playPressiveGame(key: ApiKey, pressEvents: Flow<PressiveGamePressType>): Flow<String>
    suspend fun pressiveGameBackground(key: ApiKey): Flow<SerializableColor?>
    suspend fun discoGameInstructions(key: ApiKey): Flow<DiscoGameInstruction?>
    suspend fun discoGameBackground(key: ApiKey): Flow<SerializableColor>
    suspend fun discoGamePress(key: ApiKey)
}

interface WorkshopServer {
    fun currentStage(): Flow<WorkshopStage>
    fun doPuzzleSolveAttempt(puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus>
    suspend fun setSlider(suggestedRatio: Double): SlideResult
    fun playPressiveGame(pressEvents: Flow<PressiveGamePressType>): Flow<String>
    fun pressiveGameBackground(): Flow<SerializableColor?>
    fun discoGameInstructions(): Flow<DiscoGameInstruction?>
    fun discoGameBackground(): Flow<SerializableColor>
    suspend fun discoGamePress()
}

fun WorkshopApiService.asServer(apiKey: ApiKey) = object : WorkshopServer {
    override fun currentStage(): Flow<WorkshopStage> = decoupledRpcFlow { this@asServer.currentStage() }
    override fun doPuzzleSolveAttempt(puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus> =
        decoupledRpcFlow { this@asServer.doPuzzleSolveAttempt(apiKey, puzzleName, answers) }
    override suspend fun setSlider(suggestedRatio: Double): SlideResult =
        this@asServer.setSlider(apiKey, suggestedRatio)
    override fun playPressiveGame(pressEvents: Flow<PressiveGamePressType>): Flow<String> =
        decoupledRpcFlow { this@asServer.playPressiveGame(apiKey, pressEvents) }
    override fun pressiveGameBackground(): Flow<SerializableColor?> =
        decoupledRpcFlow { this@asServer.pressiveGameBackground(apiKey) }
    override fun discoGameInstructions(): Flow<DiscoGameInstruction?> =
        decoupledRpcFlow { this@asServer.discoGameInstructions(apiKey) }
    override fun discoGameBackground(): Flow<SerializableColor> =
        decoupledRpcFlow { this@asServer.discoGameBackground(apiKey) }
    override suspend fun discoGamePress() {
        this@asServer.discoGamePress(apiKey)
    }
}

fun <T> decoupledRpcFlow(rpcFlow: suspend () -> Flow<T>): Flow<T> = channelFlow {
    streamScoped {
        rpcFlow().collect { send(it) }
    }
}

@Serializable
enum class WorkshopStage(val kotlinFile: String) {
    Registration("Registration.kt"),
    PalindromeCheckTask("PalindromeCheck.kt"),
    FindMinimumAgeOfUserTask("MinimumAgeFinding.kt"),
    FindOldestUserTask("OldestUserFinding.kt"),
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