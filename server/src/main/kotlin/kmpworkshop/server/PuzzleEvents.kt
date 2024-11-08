package kmpworkshop.server

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PuzzleStartEvent(val puzzleName: String, val startTime: Instant): WorkshopEvent()

internal fun ServerState.after(event: PuzzleStartEvent): ServerState =
    copy(puzzleStates =  puzzleStates.put(event.puzzleName, PuzzleState.Opened(
        startTime = event.startTime,
        submissions = emptyMap()
    )))