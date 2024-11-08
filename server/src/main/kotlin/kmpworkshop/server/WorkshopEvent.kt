package kmpworkshop.server

import kotlinx.serialization.Serializable

@Serializable
sealed class WorkshopEvent

fun ServerState.after(event: WorkshopEvent): ServerState = when (event) {
    is ServerWideEvents -> after(event)
    is PuzzleStartEvent -> after(event)
    is SliderGameEvent -> after(event)
    is PressiveGameEvent -> after(event)
    is DiscoGameEvent -> after(event)
}