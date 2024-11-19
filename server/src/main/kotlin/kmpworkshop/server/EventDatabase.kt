package kmpworkshop.server

import kmpworkshop.common.serverEventBackupDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Backup(val instant: Instant, val initial: ServerState, val events: List<TimedEvent>)

suspend fun eventStorageLoop(initial: ServerState, channel: ReceiveChannel<CommittedState>): Nothing = coroutineScope {
    val queue = mutableListOf<TimedEvent>()
    var current = initial
    var lastState = initial
    suspend fun doBackup(
        now: Instant = Clock.System.now(),
        backup: Backup = Backup(now, initial = current, events = queue.toList()),
    ) {
        withContext(NonCancellable) {
            queue.clear()
            generateSequence(0) { it + 1 }
                .map { File(serverEventBackupDirectory!!).resolve("$now${if (it == 0) "" else "($it)"}.json") }
                .first { !it.exists() }
                .writeText(Json.encodeToString(backup))
            current = lastState
        }
    }
    try {
        for ((oldState, event, newState) in channel) {
            if (oldState.after(event.event).droppingScheduledEventTimes() != newState.droppingScheduledEventTimes())
                launch { reportIndeterministicEvent(oldState, event.event) }
            lastState = newState
            queue.add(event)
            if (queue.size < 10) continue
            doBackup()
        }
        error("Channel completed unexpectedly")
    } catch (t: Throwable) {
        throw t
    } finally {
        if (queue.isNotEmpty()) doBackup()
        val now = Clock.System.now()
        // Because we don't trust the events to be 100% deterministic yet:
        doBackup(now, backup = Backup(now, initial = lastState, events = emptyList()))
    }
}

internal suspend fun loadInitialStateFromDatabase() = getMostRecentDatabaseFileContent()
    ?.let { runCatching { Json.decodeFromString<Backup>(it) } }
    ?.onFailure { it.printStackTrace() }
    ?.getOrNull()
    ?.lastState()
    ?: ServerState()

private suspend fun getMostRecentDatabaseFileContent(): String? = withContext(Dispatchers.IO) {
    File(serverEventBackupDirectory!!)
        .also { require(it.exists()) { "Server not set up to store events!" } } // TODO: Clearer error!
        .also { require(it.isDirectory) { "Server not set up to store events!" } } // TODO: Clearer error!
        .listFiles()!!
        .maxByOrNull { it.name }
        ?.readText()
}

private fun Backup.lastState(): ServerState = events.fold(initial) { state, event -> state.after(event.event) }