package kmpworkshop.server

import workshop.adminaccess.BugType
import workshop.adminaccess.PendingBug
import workshop.adminaccess.ReportedBug
import workshop.adminaccess.ServerState
import workshop.adminaccess.WorkshopEvent
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

fun ServerState.droppingScheduledEventTimes(): ServerState =
    copy(scheduledEvents = scheduledEvents.map { it.copy(time = Instant.DISTANT_PAST) })

fun reportError(safeState: ServerState, event: WorkshopEvent, bugs: PendingBugReports) {
    reportBug(safeState, BugType.Error(event), bugs)
}

fun reportBehavioralBug(
    initialState: ServerState,
    description: String,
    events: List<WorkshopEvent>,
    bugs: PendingBugReports,
) {
    reportBug(initialState, BugType.Behavioral(description, events), bugs)
}

fun reportIndeterministicEvent(initialState: ServerState, event: WorkshopEvent, bugs: PendingBugReports) {
    reportBug(initialState, BugType.IndeterministicEvent(event), bugs)
}

private fun reportBug(initialState: ServerState, type: BugType, bugs: PendingBugReports) {
    bugs.add(PendingBug.Server(ReportedBug(UUID.randomUUID().toString(), initialState, type, Clock.System.now())))
}
