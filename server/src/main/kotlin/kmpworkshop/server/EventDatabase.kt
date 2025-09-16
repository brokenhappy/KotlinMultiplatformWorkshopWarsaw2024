@file:OptIn(ExperimentalTime::class)

package kmpworkshop.server

import kmpworkshop.common.serverEventBackupDirectory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
data class Backup(val instant: Instant, val initial: ServerState, val events: List<TimedEvent>)

internal data class BackupRequest(/** null means we want to stop */ val backup: Backup?, val isLast: Boolean)

internal suspend fun store(backupRequests: ReceiveChannel<BackupRequest>): Nothing {
    withContext(NonCancellable + Dispatchers.IO) {
        for (request in backupRequests) {
            val backup = request.backup ?: break
            generateSequence(0) { it + 1 }
                .map { File(serverEventBackupDirectory!!).resolve(backupFileName(backup, it)) }
                .first { !it.exists() }
                .writeText(Json.encodeToString(request.backup))
            if (request.isLast) break
        }
    }
    awaitCancellation()
}

private fun backupFileName(backup: Backup, index: Int) =
    "${Json.encodeToString(backup.instant).removeSurrounding("\"")}${if (index == 0) "" else "($index)"}.json"

internal suspend fun withBackupLoop(
    initial: ServerState,
    channel: ReceiveChannel<CommittedState>,
    block: suspend CoroutineScope.(backups: ReceiveChannel<BackupRequest>, trailingBackup: Flow<Backup>) -> Nothing,
): Nothing = coroutineScope {
    val backupsChannel = Channel<BackupRequest>()
    val trailingBackup = MutableStateFlow(Backup(Clock.System.now(), initial = initial, events = emptyList()))
    launch {
        block(backupsChannel, trailingBackup)
    }
    try {
        for ((oldState, event, newState) in channel) {
            if (oldState.after(event.event).droppingScheduledEventTimes() != newState.droppingScheduledEventTimes())
                launch { reportIndeterministicEvent(oldState, event.event) }
            trailingBackup.update { it.copy(events = it.events + event) }
            if (trailingBackup.value.events.size < 10) continue
            backupsChannel.send(BackupRequest(trailingBackup.value, isLast = false))
            trailingBackup.value = Backup(Clock.System.now(), initial = newState, events = emptyList())
        }
        error("Channel completed unexpectedly")
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        t.printStackTrace()
        throw t
    } finally {
        withContext(NonCancellable) {
            backupsChannel.send(BackupRequest(
                trailingBackup.value.takeIf { it.events.isNotEmpty() },
                isLast = true,
            ))
        }
    }
}

internal suspend fun loadInitialStateFromDatabase() = getMostRecentDatabaseFileContent()
    ?.let { runCatching { Json.decodeFromString<Backup>(it) } }
    ?.onFailure { it.printStackTrace() }
    ?.getOrNull()
    ?.lastState()
    ?: ServerState()

private suspend fun getMostRecentDatabaseFileContent(): String? = withContext(Dispatchers.IO) {
   getAllDatabaseFilesInChronologicalOrder().lastOrNull()?.readText()
}

private suspend fun getAllDatabaseFilesInChronologicalOrder(): List<File> = withContext(Dispatchers.IO) {
    File(serverEventBackupDirectory!!)
        .also { require(it.exists()) { "Server not set up to store events!" } } // TODO: Clearer error!
        .also { require(it.isDirectory) { "Server not set up to store events!" } } // TODO: Clearer error!
        .listFiles()!!
        .sortedBy { it.name }
}

private fun Backup.lastState(): ServerState = events.fold(initial) { state, event -> state.after(event.event) }

sealed class BackupFetchResult {
    data class Success(val backup: Backup): BackupFetchResult()
    data class Outdated(val startTime: Instant): BackupFetchResult()
}

suspend fun getAllBackups(): List<BackupFetchResult> = withContext(Dispatchers.IO) {
    getAllDatabaseFilesInChronologicalOrder()
        .map {
            try {
                BackupFetchResult.Success(Json.decodeFromString<Backup>(it.readText()))
            } catch (e: Exception) {
                BackupFetchResult.Outdated(Json.decodeFromString(""""${it.nameWithoutExtension.substringBeforeLast('(')}""""))
            }
        }
}