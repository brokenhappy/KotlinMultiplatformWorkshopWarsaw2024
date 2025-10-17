package workshop.adminaccess

import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.json.JsonElement

@Rpc interface AdminAccess {
    /** The full current state of the app */
    fun serverState(password: String): Flow<ServerState>
    fun soundEvents(password: String): Flow<SoundPlayEvent>
    suspend fun heartbeat()
    /**
     * Either:
     *  - Schedules the event if [event] !is [WorkshopEventWithResult] and returns null.
     *  - Otherwise, it waits for the result of the event, and returns it as a JsonElement.
     */
    suspend fun fire(password: String, event: WorkshopEvent): JsonElement?
}