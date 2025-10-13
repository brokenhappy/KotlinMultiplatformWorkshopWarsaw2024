package kmpworkshop.server

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import workshop.adminaccess.AdminAccess
import workshop.adminaccess.OnEvent
import workshop.adminaccess.ServerState
import workshop.adminaccess.WorkshopEvent
import workshop.adminaccess.WorkshopEventWithResult
import workshop.adminaccess.fire
import workshop.adminaccess.schedule

fun adminAccess(serverState: Flow<ServerState>, onEvent: OnEvent): AdminAccess = object : AdminAccess {
    override fun serverState(password: String): Flow<ServerState> = serverState.also { _ ->
        if (password != System.getenv("admin_access_password")) error("Incorrect password")
    }

    override suspend fun fire(password: String, event: WorkshopEvent): JsonElement? = when {
        password != System.getenv("admin_access_password") -> error("Incorrect password")
        event is WorkshopEventWithResult<*> -> onEvent.fireRaw(event)
        else -> {
            onEvent.schedule(event)
            null
        }
    }
}

private suspend fun <T> OnEvent.fireRaw(event: WorkshopEventWithResult<T>): JsonElement =
    Json.encodeToJsonElement(event.serializer, fire(event))
