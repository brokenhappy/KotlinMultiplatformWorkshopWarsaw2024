package kmpworkshop.server

import kotlinx.serialization.Serializable
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

typealias OnEvent = (ScheduledWorkshopEvent) -> Unit

@Serializable
sealed class WorkshopEvent
@Serializable
sealed class WorkshopEventWithResult<Result>: WorkshopEvent() {
    abstract fun applyWithResultTo(oldState: ServerState): Pair<ServerState, Result>
}

sealed class ScheduledWorkshopEvent {
    data class IgnoringResult(val event: WorkshopEvent) : ScheduledWorkshopEvent()
    data class AwaitingResult<T>(val event: WorkshopEventWithResult<T>, val continuation: Continuation<T>) : ScheduledWorkshopEvent()
}

fun OnEvent.schedule(event: WorkshopEvent) {
    this(ScheduledWorkshopEvent.IgnoringResult(event))
}

suspend fun <T> OnEvent.fire(event: WorkshopEventWithResult<T>): T = suspendCoroutine { continuation ->
    this(ScheduledWorkshopEvent.AwaitingResult(event, continuation))
}

fun ServerState.after(event: WorkshopEvent): ServerState = when (event) {
    is ServerWideEvents -> after(event)
    is PuzzleStartEvent -> after(event)
    is SliderGameEvent -> after(event)
    is PressiveGameEvent -> after(event)
    is DiscoGameEvent -> after(event)
    is SoundPlayEvents -> after(event)
    is WorkshopEventWithResult<*> -> event.applyWithResultTo(this).first
}