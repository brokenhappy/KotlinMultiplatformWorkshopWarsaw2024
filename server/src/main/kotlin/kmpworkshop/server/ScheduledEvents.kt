package kmpworkshop.server

import kmpworkshop.common.Color
import kmpworkshop.server.TimedEventType.PressiveGameTickEvent
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.sound.sampled.AudioSystem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.use

suspend fun performScheduledEvents(serverState: MutableStateFlow<ServerState>, eventBus: ReceiveChannel<WorkshopEvent>): Nothing {
    coroutineScope {
        launch {
            eventBus.consumeEach { event -> serverState.update { it.after(event) } }
        }
        serverState
            .map { it.scheduledEvents.minByOrNull { it.time } }
            .distinctUntilChangedBy { it?.time }
            .collectLatest { firstScheduledEvent ->
                if (firstScheduledEvent == null) return@collectLatest
                delayUntil(firstScheduledEvent.time)
                serverState.update {
                    it.copy(scheduledEvents = it.scheduledEvents - firstScheduledEvent).after(firstScheduledEvent.type)
                }
            }
    }
    error("Should not be reached")
}

internal data class InProgressScheduling(val stateWithoutEventScheduled: ServerState, val event: TimedEventType)
internal fun InProgressScheduling.after(delay: Duration): ServerState = stateWithoutEventScheduled.copy(
    scheduledEvents = stateWithoutEventScheduled.scheduledEvents + TimedEvent(Clock.System.now() + delay, event)
)
internal fun ServerState.scheduling(event: TimedEventType): InProgressScheduling = InProgressScheduling(this, event)

internal val discoGamePressTimeout = 2.5.seconds
private val danceFloorChangeInterval = 0.5.seconds

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
                    null -> state.order.firstOrNull()
                    else -> state.order.getOrNull(state.order.indexOfFirst { it == current } + 1)
                }
            )
        ).scheduling(PressiveGameTickEvent).after(delayForNextEvent(state))
    }
    TimedEventType.DiscoGameBackgroundTickEvent -> when (val state = discoGameState) {
        DiscoGameState.Done,
        DiscoGameState.NotStarted -> this
        is DiscoGameState.InProgress -> copy(
            discoGameState = state.copy(
                orderedParticipants = state
                    .orderedParticipants
                    .map { it.copy(color = discoColors.random()) }
            ),
        ).scheduling(TimedEventType.DiscoGameBackgroundTickEvent).after(danceFloorChangeInterval)
    }
    TimedEventType.DiscoGamePressTimeoutEvent -> when (val state = discoGameState) {
        DiscoGameState.Done,
        DiscoGameState.NotStarted -> this
        is DiscoGameState.InProgress -> copy(discoGameState = state.restartingInstructions())
            .scheduling(TimedEventType.DiscoGamePressTimeoutEvent).after(discoGamePressTimeout)
    }
    is TimedEventType.PlaySuccessSound -> this.also { GlobalScope.launch { playSuccessSound() } }
}

val discoColors = listOf(
    Color(0, 0, 0),
    Color(255, 0, 0),
    Color(0, 255, 0),
    Color(0, 0, 255),
    Color(0, 255, 255),
    Color(255, 0, 255),
    Color(255, 255, 0),
    Color(255, 255, 255),
)

private fun delayForNextEvent(lastState: PressiveGameState.ThirdGameInProgress): Duration = when {
    lastState.participantThatIsBeingRung == lastState.order.last() -> 1.seconds // Wait a bit in between cycles
    else -> 150.milliseconds
}

private suspend fun delayUntil(time: Instant) {
    (time - Clock.System.now())
        .takeIf { it.isPositive() }
        ?.also { timeUntilEvent -> delay(timeUntilEvent) }
}

private suspend fun playSuccessSound() {
    AudioSystem.getClip().use { clip ->
        clip.open(AudioSystem.getAudioInputStream((object {})::class.java.getResourceAsStream("/success.wav")))
        clip.start()
        clip.drain()
        delay(3.seconds)
    }
}
