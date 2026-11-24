package workshop.adminaccess

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** A server event failure or behavioral bug awaiting admin download. */
@Serializable
data class ReportedBug(
    val id: String,
    val initialState: ServerState,
    val type: BugType,
    val moment: Instant,
)

@Serializable
sealed class BugType {
    @Serializable
    data class Behavioral(val description: String, val events: List<WorkshopEvent>) : BugType()

    @Serializable
    data class IndeterministicEvent(val event: WorkshopEvent) : BugType()

    @Serializable
    data class Error(val failingEvent: WorkshopEvent) : BugType()
}
