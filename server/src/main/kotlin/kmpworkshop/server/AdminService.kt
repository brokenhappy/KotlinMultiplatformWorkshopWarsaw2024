package kmpworkshop.server

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kmpworkshop.common.superSecretFallbackPassword
import workshop.adminaccess.AdminAccess
import workshop.adminaccess.OnEvent
import workshop.adminaccess.ServerState
import workshop.adminaccess.SoundPlayEvent
import workshop.adminaccess.StoredClientBugReport
import workshop.adminaccess.WorkshopEvent
import workshop.adminaccess.WorkshopEventWithResult
import workshop.adminaccess.fire
import workshop.adminaccess.schedule

private val adminAccessPassword = System.getenv("admin_access_password") ?: superSecretFallbackPassword()

fun adminAccess(
    serverState: Flow<ServerState>,
    onEvent: OnEvent,
    sounds: Flow<SoundPlayEvent>,
    clientBugReports: Flow<StoredClientBugReport>,
): AdminAccess = object : AdminAccess {
    override fun serverState(password: String): Flow<ServerState> = serverState.also { _ ->
        if (password != adminAccessPassword) error("Incorrect password")
    }

    override fun soundEvents(password: String): Flow<SoundPlayEvent> = sounds.also { _ ->
        if (password != adminAccessPassword) error("Incorrect password")
    }

    override fun clientBugReports(password: String): Flow<StoredClientBugReport> = clientBugReports.also { _ ->
        if (password != adminAccessPassword) error("Incorrect password")
    }

    override suspend fun heartbeat() { /* pong! */ }

    override suspend fun fire(password: String, event: WorkshopEvent): JsonElement? = when {
        password != adminAccessPassword -> error("Incorrect password")
        event is WorkshopEventWithResult<*> -> onEvent.fireRaw(event)
        else -> {
            onEvent.schedule(event)
            null
        }
    }
}

private suspend fun <T> OnEvent.fireRaw(event: WorkshopEventWithResult<T>): JsonElement =
    Json.encodeToJsonElement(event.serializer, fire(event))
