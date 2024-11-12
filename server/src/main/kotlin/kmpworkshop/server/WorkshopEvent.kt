package kmpworkshop.server

import kotlinx.serialization.Serializable
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

typealias OnEvent = (ScheduledWorkshopEvent) -> Unit

@Serializable
sealed class WorkshopEvent

sealed class ScheduledWorkshopEvent {
    data class IgnoringResult(val event: WorkshopEvent) : ScheduledWorkshopEvent()
    data class AwaitingResult(val event: WorkshopEvent, val continuation: Continuation<ServerState>) : ScheduledWorkshopEvent()
}

fun OnEvent.schedule(event: WorkshopEvent) {
    this(ScheduledWorkshopEvent.IgnoringResult(event))
}

suspend fun OnEvent.fire(event: WorkshopEvent): ServerState = suspendCoroutine { continuation ->
    this(ScheduledWorkshopEvent.AwaitingResult(event, continuation))
}

fun ServerState.after(event: WorkshopEvent): ServerState = when (event) {
    is ServerWideEvents -> after(event)
    is PuzzleStartEvent -> after(event)
    is SliderGameEvent -> after(event)
    is PressiveGameEvent -> after(event)
    is DiscoGameEvent -> after(event)
}