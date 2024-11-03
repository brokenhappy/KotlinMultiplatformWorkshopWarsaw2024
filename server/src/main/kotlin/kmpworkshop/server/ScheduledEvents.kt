package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.server.TimedEventType.PressiveGameTickEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal suspend fun performScheduledEvents(serverState: MutableStateFlow<ServerState>): Nothing {
    serverState
        .map { it.scheduledEvents.minBy { it.time } }
        .distinctUntilChangedBy { it.time }
        .collectLatest { firstScheduledEvent ->
            delayUntil(firstScheduledEvent.time)
            serverState.update {
                it.copy(scheduledEvents = it.scheduledEvents - firstScheduledEvent).after(firstScheduledEvent.type)
            }
        }
    error("Should not be reached")
}

internal data class InProgressScheduling(val stateWithoutEventScheduled: ServerState, val event: TimedEventType)
internal fun InProgressScheduling.after(delay: Duration): ServerState = stateWithoutEventScheduled.copy(
    scheduledEvents = stateWithoutEventScheduled.scheduledEvents + TimedEvent(Clock.System.now() + delay, event)
)
internal fun ServerState.scheduling(event: TimedEventType): InProgressScheduling = InProgressScheduling(this, event)

private fun ServerState.after(event: TimedEventType): ServerState = when (event) {
    PressiveGameTickEvent -> when (val state = pressiveGameState) {
        is PressiveGameState.FirstGameDone,
        is PressiveGameState.FirstGameInProgress,
        PressiveGameState.NotStarted,
        PressiveGameState.SecondGameDone,
        is PressiveGameState.SecondGameInProgress,
        PressiveGameState.ThirdGameDone -> this
        is PressiveGameState.ThirdGameInProgress -> copy(
            pressiveGameState = state.copy(
                participantThatIsBeingRung = when (val current = state.participantThatIsBeingRung) {
                    null -> state.order.getOrNull(state.order.indexOfFirst { it == current } + 1)
                    else -> state.order.firstOrNull()
                }
            )
        ).scheduling(PressiveGameTickEvent).after(delayForNextEvent(state.participantThatIsBeingRung))
    }
}

private fun delayForNextEvent(participantThatIsBeingRung: ApiKey?): Duration = when (participantThatIsBeingRung) {
    null -> 1.seconds // Wait a bit in between cycles
    else -> 150.milliseconds
}

private suspend fun delayUntil(time: Instant) {
    (Clock.System.now() - time)
        .takeIf { it.isPositive() }
        ?.also { timeUntilEvent -> delay(timeUntilEvent) }
}