@file:OptIn(ExperimentalTime::class)

package kmpworkshop.server

import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
data class PuzzleStartEvent(val puzzleName: String, val startTime: Instant): WorkshopEvent()

internal fun ServerState.after(event: PuzzleStartEvent): ServerState =
    copy(puzzleStates =  puzzleStates.put(event.puzzleName, PuzzleState.Opened(
        startTime = event.startTime,
        submissions = emptyMap()
    )))