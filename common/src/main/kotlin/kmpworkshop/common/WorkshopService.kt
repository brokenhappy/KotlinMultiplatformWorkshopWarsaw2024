package kmpworkshop.common

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.rpc.RPC
import kotlinx.serialization.json.JsonElement

interface WorkshopService : RPC {
    suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult
    suspend fun verifyRegistration(key: ApiKey): NameVerificationResult
    suspend fun doPuzzleSolveAttempt(key: ApiKey, puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus>
    suspend fun setSlider(key: ApiKey, suggestedRatio: Double): SlideResult
    suspend fun playPressiveGame(key: ApiKey, pressEvents: Flow<PressiveGamePressType>): Flow<String>
    suspend fun pressiveGameBackground(key: ApiKey): Flow<Color?>
}

@Serializable
data class Color(val red: Int, val green: Int, val blue: Int)

@Serializable
enum class PressiveGamePressType {
    SinglePress, DoublePress, LongPress;
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
    data object NameAlreadyExists : NameVerificationResult()
}

@Serializable
data class ApiKey(val stringRepresentation: String)

// We don't want to burden the user with @Serializable, so we hide it here
@Serializable
data class SerializableUser(val name: String, val age: Int) {
    override fun toString(): String = "User(name=$name, age=$age)"
}