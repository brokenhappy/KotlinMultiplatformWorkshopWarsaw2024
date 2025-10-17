@file:OptIn(ExperimentalTime::class)

package workshop.adminaccess

import kmpworkshop.common.*
import kotlinx.serialization.Serializable
import kotlin.collections.plus
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
data class Participant(val name: String, val apiKey: ApiKey, val team: TeamColor = TeamColor.entries.first())

@Serializable
data class ServerState(
    val participants: List<Participant> = emptyList(),
    val deactivatedParticipants: List<Participant> = emptyList(),
    val unverifiedParticipants: List<Participant> = emptyList(),
    val currentStage: WorkshopStage = WorkshopStage.Registration,
    val settings: ServerSettings = ServerSettings(),
    val scheduledEvents: List<TimedEvent> = emptyList(),
    val puzzleStates: Map<String, PuzzleState> = emptyMap(),
    val tables: List<Table> = emptyList(),
    val teamCount: Int = 2,
)

@Serializable
data class Table(val x: Int, val y: Int, val assignee: ApiKey?)

@Serializable
data class TimedEvent(
    val time: Instant,
    val event: WorkshopEvent,
)

@Serializable
sealed class PuzzleState {
    @Serializable
    data object Unopened: PuzzleState()
    @Serializable
    data class Opened(val startTime: Instant, val submissions: Map<ApiKeyString, Instant>): PuzzleState()
}

typealias ApiKeyString = String // Because ApiKey is not serializable when used as a Map key

@Serializable
data class ServerSettings(
    /** Value is `in -1..1`. Negative means darker, Positive means lighter */
    val dimmingRatio: Float = 0f,
    val zoom: Float = 1f,
)

@Serializable
data class Submissions(
    val startTime: Instant,
    val participants: List<Participant>,
    val completedSubmissions: Map<ApiKey, Instant>,
)

@Serializable
data class SliderState(val gapOffset: Double, val position: Double)

fun ServerState.scheduling(event: WorkshopEvent): InProgressScheduling =
    InProgressScheduling(this, event, onlyASingleOfThisType = false)

data class InProgressScheduling(
    val stateWithoutEventScheduled: ServerState,
    val event: WorkshopEvent,
    val onlyASingleOfThisType: Boolean
)

fun InProgressScheduling.after(delay: Duration): ServerState = stateWithoutEventScheduled.copy(
    scheduledEvents = stateWithoutEventScheduled
        .scheduledEvents
        .applyIf({ onlyASingleOfThisType }) { it.filterNot { it.event.javaClass == event.javaClass } }
            + TimedEvent(Clock.System.now() + delay, event)
)

fun ServerState.schedulingSingle(event: WorkshopEvent): InProgressScheduling =
    InProgressScheduling(this, event, onlyASingleOfThisType = true)
