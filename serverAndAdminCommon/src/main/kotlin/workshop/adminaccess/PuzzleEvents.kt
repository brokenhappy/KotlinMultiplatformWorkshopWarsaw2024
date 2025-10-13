@file:OptIn(ExperimentalTime::class)

package workshop.adminaccess

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

internal fun <K, V> Map<K, V>.put(key: K, value: V): Map<K, V> = this + (key to value)