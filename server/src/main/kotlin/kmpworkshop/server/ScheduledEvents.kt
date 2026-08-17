package kmpworkshop.server

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import workshop.adminaccess.*
import kotlin.time.Clock
import kotlin.time.Instant

suspend fun mainEventLoopWritingTo(
    serverState: MutableStateFlow<ServerState>,
    eventBus: ReceiveChannel<ScheduledWorkshopEvent>,
    onSoundEvent: (SoundPlayEvent) -> Unit,
    onEvent: OnEvent,
): Nothing = coroutineScope {
    launch {
        for (scheduledEvent in eventBus) {
            when (scheduledEvent) {
                is ScheduledWorkshopEvent.AwaitingResult<*> -> {
                    serverState.applyEventWithResult(applicationScope = this, scheduledEvent)
                }
                is ScheduledWorkshopEvent.IgnoringResult -> {
                    serverState.update { oldState ->
                        try {
                            oldState.after(scheduledEvent.event, onSoundEvent)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            launch { reportError(oldState, scheduledEvent.event) }
                            oldState
                        }
                    }
                }
            }
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
): Result<T> {
    val result = runCatching {
        var result: T? = null
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
            scheduledEvent.continuation.context.ensureActive() // Don't apply the change if the request got canceled.
            nextState
        }

        result as T
    }
    // Launch to make sure we keep the important Event loop running.
    applicationScope.launch { scheduledEvent.continuation.resumeWith(result) }
    return result
}

private suspend fun delayUntil(time: Instant) {
    (time - Clock.System.now())
        .takeIf { it.isPositive() }
        ?.also { timeUntilEvent -> delay(timeUntilEvent) }
}
