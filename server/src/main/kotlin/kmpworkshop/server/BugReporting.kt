package kmpworkshop.server

import kmpworkshop.common.bugDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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
    data class Error(val failingEvent: WorkshopEvent): BugType()
}

suspend fun reportError(safeState: ServerState, event: WorkshopEvent) {
    reportBug(ReportedBug(safeState, BugType.Error(event), Clock.System.now()))
}

suspend fun reportBehavioralBug(initialState: ServerState, description: String, events: List<WorkshopEvent>) {
    reportBug(ReportedBug(initialState, BugType.Behavioral(description, events), Clock.System.now()))
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