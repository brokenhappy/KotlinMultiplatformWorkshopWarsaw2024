package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.getEnvironment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
internal data class Participant(val name: String, val apiKey: ApiKey)

@Serializable
internal data class ServerState(
    val participants: List<Participant>,
    val unverifiedParticipants: List<Participant>,
)

private val serverStateLock = Mutex()
private var serverState by fileBackedProperty<ServerState>(
    filePath = "/Users/Wout.Werkman/Documents/KotlinMultiplatformWorkshopWarsaw2024/participantsDatabase.csv",
    defaultValue = { ServerState(emptyList(), emptyList()) },
)

internal suspend fun <T> updateServerStateAndGetValue(update: (ServerState) -> Pair<ServerState, T>): T =
    serverStateLock.withLock {
        val (newState, value) = update(serverState)
        if (newState != serverState) serverState = newState
        value
    }
