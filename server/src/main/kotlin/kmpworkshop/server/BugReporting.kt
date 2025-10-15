@file:OptIn(ExperimentalTime::class)

package kmpworkshop.server

import kmpworkshop.common.bugDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import workshop.adminaccess.ServerState
import workshop.adminaccess.WorkshopEvent
import java.io.File
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
data class ReportedBug(
    val initialState: ServerState,
    val type: BugType,
    val moment: Instant,
)

@Serializable
sealed class BugType {
    @Serializable
    data class Behavioral(val description: String, val events: List<WorkshopEvent>): BugType()
    @Serializable
    data class IndeterministicEvent(val event: WorkshopEvent): BugType()
    @Serializable
    data class Error(val failingEvent: WorkshopEvent): BugType()
}

fun ServerState.droppingScheduledEventTimes(): ServerState =
    copy(scheduledEvents = scheduledEvents.map { it.copy(time = Instant.DISTANT_PAST) })

suspend fun reportError(safeState: ServerState, event: WorkshopEvent) {
    reportBug(ReportedBug(safeState, BugType.Error(event), Clock.System.now()))
}

suspend fun reportBehavioralBug(initialState: ServerState, description: String, events: List<WorkshopEvent>) {
    reportBug(ReportedBug(initialState, BugType.Behavioral(description, events), Clock.System.now()))
}

suspend fun reportIndeterministicEvent(initialState: ServerState, event: WorkshopEvent) {
    reportBug(ReportedBug(initialState, BugType.IndeterministicEvent(event), Clock.System.now()))
}

suspend fun reportBug(bug: ReportedBug) {
    if (!File(bugDirectory!!).exists()) error("""
        Server not set up to store bugs! But the following bug happened:
        ${Json.encodeToString(bug)}
    """.trimIndent())
    withContext(Dispatchers.IO) {
        val file = generateSequence(0) { it + 1 }
            .map { File("$bugDirectory").resolve("Bug: ${bug.moment}${if (it == 0) "" else "($it)"}.json") }
            .first { !it.exists() }
        file.writeText(Json.encodeToString(bug))
    }
}