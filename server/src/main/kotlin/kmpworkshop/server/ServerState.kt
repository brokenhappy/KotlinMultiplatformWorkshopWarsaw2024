package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
internal data class Participant(val name: String, val apiKey: ApiKey)

@Serializable
internal data class ServerState(
    val participants: List<Participant> = emptyList(),
    val unverifiedParticipants: List<Participant> = emptyList(),
    val currentStage: WorkshopStage = WorkshopStage.Registration,
    val puzzleStates: Map<String, PuzzleState> = emptyMap(),
)

@Serializable
sealed class PuzzleState {
    @Serializable
    data object Unopened: PuzzleState()
    @Serializable
    data class Opened(val startTime: Instant, val submissions: Map</* Is ApiKey, but can't be serialized to JSON */String, Instant>): PuzzleState()
}

@Serializable
internal enum class WorkshopStage(val kotlinFile: String) {
    Registration("Registration.kt"),
    PalindromeCheckTask("PalindromeCheck.kt"),
    FindMinimumAgeOfUserTask("MinimumAgeFinding.kt"),
}

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
    filePath = "/Users/Wout.Werkman/Documents/KotlinMultiplatformWorkshopWarsaw2024/participantsDatabase.csv",
    defaultValue = { ServerState() },
)
private var serverState by serverStateProperty
