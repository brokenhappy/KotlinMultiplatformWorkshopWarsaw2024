package workshop.adminaccess

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.coroutines.Continuation

typealias OnEvent = (ScheduledWorkshopEvent) -> Unit

@Serializable
sealed class WorkshopEvent
@Serializable
sealed class WorkshopEventWithResult<Result>: WorkshopEvent() {
    abstract val serializer: KSerializer<Result>
    // TODO: Fix this ugly-ass function, it shouldn't be here and currently tightly couples this module to server implementation
    abstract fun applyWithResultTo(oldState: ServerState): Pair<ServerState, Result>
}

sealed class ScheduledWorkshopEvent {
    data class IgnoringResult(val event: WorkshopEvent) : ScheduledWorkshopEvent()
    data class AwaitingResult<T>(val event: WorkshopEventWithResult<T>, val continuation: Continuation<T>) : ScheduledWorkshopEvent()
}

fun OnEvent.schedule(event: WorkshopEvent) {
    this(ScheduledWorkshopEvent.IgnoringResult(event))
}

suspend fun <T> OnEvent.fire(event: WorkshopEventWithResult<T>): T = suspendCancellableCoroutine { continuation ->
    this(ScheduledWorkshopEvent.AwaitingResult(event, continuation))
}

fun ServerState.after(event: WorkshopEvent, onSoundEvent: (SoundPlayEvent) -> Unit): ServerState = when (event) {
    is ServerWideEvents -> after(event, onSoundEvent)
    is PuzzleStartEvent -> after(event)
    is SoundPlayEvent -> this.also { onSoundEvent(event) }
    is WorkshopEventWithResult<*> -> event.applyWithResultTo(this).first
}