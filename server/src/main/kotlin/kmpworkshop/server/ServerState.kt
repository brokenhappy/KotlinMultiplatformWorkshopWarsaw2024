package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.getEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
internal data class Participant(val name: String, val apiKey: ApiKey)

@Serializable
internal data class ServerState(
    val participants: List<Participant> = emptyList(),
    val unverifiedParticipants: List<Participant> = emptyList(),
    val currentStage: WorkshopStage = WorkshopStage.Registration,
    val puzzleStates: Map<String, PuzzleState> = emptyMap(),
    val sliderGameState: SliderGameState = SliderGameState.NotStarted,
)

@Serializable
sealed class PuzzleState {
    @Serializable
    data object Unopened: PuzzleState()
    @Serializable
    data class Opened(val startTime: Instant, val submissions: Map<ApiKeyString, Instant>): PuzzleState()
}

typealias ApiKeyString = String // Because ApiKey is not serializable when used as a Map key

@Serializable
internal enum class WorkshopStage(val kotlinFile: String) {
    Registration("Registration.kt"),
    PalindromeCheckTask("PalindromeCheck.kt"),
    FindMinimumAgeOfUserTask("MinimumAgeFinding.kt"),
    FindOldestUserTask("OldestUserFinding.kt"),
    SliderGameStage("SliderGameClient.kt"),
}

@Serializable
internal sealed class SliderGameState {
    @Serializable
    data object NotStarted : SliderGameState()
    @Serializable
    data class InProgress(
        val participantStates: Map<ApiKeyString, SliderState>,
        val pegLevel: Int,
        val pegPosition: Double,
    ) : SliderGameState()
    @Serializable
    data class Done(val lastState: InProgress) : SliderGameState()
}

@Serializable
data class SliderState(val gapOffset: Double, val position: Double)

internal fun serverState(): Flow<ServerState> = serverStateProperty
internal suspend fun <T> updateServerStateAndGetValue(update: (ServerState) -> Pair<ServerState, T>): T =
    serverStateLock.withLock {
        val (newState, value) = update(serverState)
        if (newState != serverState) serverState = newState
        value
    }

internal suspend inline fun updateServerState(crossinline update: (ServerState) -> ServerState) {
    updateServerStateAndGetValue { update(it) to Unit }
}

private val serverStateLock = Mutex()
private val serverStateProperty = fileBackedProperty<ServerState>(
    filePath = getEnvironment()!!["server-database-file"]!!,
    defaultValue = { ServerState() },
)
private var serverState by serverStateProperty
