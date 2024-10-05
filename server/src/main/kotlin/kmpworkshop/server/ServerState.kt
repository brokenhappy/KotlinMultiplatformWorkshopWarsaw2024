package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
internal data class Participant(val name: String, val apiKey: ApiKey)

@Serializable
internal data class ServerState(
    val participants: List<Participant>,
    val unverifiedParticipants: List<Participant>,
    val currentStage: WorkshopStage,
)

@Serializable
internal enum class WorkshopStage(val kotlinFile: String) {
    Registration("Registration.kt"),
    PalindromeCheckTask("PalindromeCheck.kt"),
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
    defaultValue = { ServerState(emptyList(), emptyList(), WorkshopStage.Registration) },
)
private var serverState by serverStateProperty
