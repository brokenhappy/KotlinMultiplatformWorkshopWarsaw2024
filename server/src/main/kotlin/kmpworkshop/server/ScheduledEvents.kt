package kmpworkshop.server

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

suspend fun mainEventLoopWithCommittedStateChannelWritingTo(
    serverState: MutableStateFlow<ServerState>,
    eventBus: ReceiveChannel<ScheduledWorkshopEvent>,
    onEvent: OnEvent,
    block: suspend CoroutineScope.(initial: ServerState, Channel<CommittedState>) -> Unit,
): Nothing = coroutineScope {
    val events = Channel<CommittedState>()
    launch {
        try {
            val initial = loadInitialStateFromDatabase()
            if (initial != ServerState()) serverState.value = initial
            block(initial, events)
        } catch (t: Throwable) {
            t.printStackTrace()
            throw t
        }
    }
    mainEventLoopWritingTo(serverState, eventBus, onCommittedState = { launch { events.send(it) } }, onEvent = onEvent)
}

suspend fun mainEventLoopWritingTo(
    serverState: MutableStateFlow<ServerState>,
    eventBus: ReceiveChannel<ScheduledWorkshopEvent>,
    onCommittedState: (CommittedState) -> Unit = {},
    onEvent: OnEvent,
): Nothing = coroutineScope {
    launch {
        try {
            for (scheduledEvent in eventBus) {
                when (scheduledEvent) {
                    is ScheduledWorkshopEvent.AwaitingResult<*> -> {
                        serverState.applyEventWithResult(applicationScope = this, scheduledEvent, onCommittedState)
                    }
                    is ScheduledWorkshopEvent.IgnoringResult -> {
                        var persistedState: CommittedState? = null
                        serverState.update { oldState ->
                            try {
                                oldState.after(scheduledEvent.event).also { newState ->
                                    persistedState = CommittedState(
                                        oldState,
                                        TimedEvent(Clock.System.now(), scheduledEvent.event),
                                        newState,
                                    )
                                }
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                launch { reportError(oldState, scheduledEvent.event) }
                                oldState
                            }
                        }
                        persistedState?.let(onCommittedState)
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            throw t
        }
    }
    serverState
        .map { it.scheduledEvents.minByOrNull { it.time } }
        .distinctUntilChangedBy { it?.time }
        .collectLatest { firstScheduledEvent ->
            try {
                if (firstScheduledEvent == null) return@collectLatest
                delayUntil(firstScheduledEvent.time)
                onEvent.schedule(ApplyScheduledEvent(firstScheduledEvent))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                t.printStackTrace()
                throw t
            }
        }
    error("Should not be reached")
}

private fun <T> MutableStateFlow<ServerState>.applyEventWithResult(
    applicationScope: CoroutineScope,
    scheduledEvent: ScheduledWorkshopEvent.AwaitingResult<T>,
    onCommittedState: (CommittedState) -> Unit,
): Result<T> {
    val result = runCatching {
        var result: T? = null
        var persistedState: CommittedState? = null
        this@applyEventWithResult.updateAndGet { oldState ->
            val (nextState, value) = try {
                scheduledEvent.event.applyWithResultTo(oldState)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                applicationScope.launch { reportError(oldState, scheduledEvent.event) }
                throw t
            }
            result = value
            persistedState = CommittedState(
                oldState,
                TimedEvent(Clock.System.now(), scheduledEvent.event),
                nextState,
            )
            scheduledEvent.continuation.context.ensureActive() // Don't apply the change if the request got canceled.
            nextState
        }
        persistedState?.let(onCommittedState)

        result as T
    }
    // Launch to make sure we keep the important Event loop running.
    applicationScope.launch { scheduledEvent.continuation.resumeWith(result) }
    return result
}

data class CommittedState(val old: ServerState, val event: TimedEvent, val new: ServerState)

internal data class InProgressScheduling(
    val stateWithoutEventScheduled: ServerState,
    val event: WorkshopEvent,
    val onlyASingleOfThisType: Boolean
)
internal fun InProgressScheduling.after(delay: Duration): ServerState = stateWithoutEventScheduled.copy(
    scheduledEvents = stateWithoutEventScheduled
        .scheduledEvents
        .applyIf({ onlyASingleOfThisType }) { it.filterNot { it.event.javaClass == event.javaClass } }
        + TimedEvent(Clock.System.now() + delay, event)
)
internal fun ServerState.scheduling(event: WorkshopEvent): InProgressScheduling =
    InProgressScheduling(this, event, onlyASingleOfThisType = false)
internal fun ServerState.schedulingSingle(event: WorkshopEvent): InProgressScheduling =
    InProgressScheduling(this, event, onlyASingleOfThisType = true)

private suspend fun delayUntil(time: Instant) {
    (time - Clock.System.now())
        .takeIf { it.isPositive() }
        ?.also { timeUntilEvent -> delay(timeUntilEvent) }
}

