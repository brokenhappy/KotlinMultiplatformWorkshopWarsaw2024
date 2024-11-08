package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
sealed class SliderGameEvent : WorkshopEvent() {
    @Serializable
    data class Finished(val lastGameState: SliderGameState.InProgress) : SliderGameEvent()
    @Serializable
    data object Start : SliderGameEvent()
    @Serializable
    data object Restart : SliderGameEvent()
}

fun ServerState.after(event: SliderGameEvent): ServerState = when (event) {
    is SliderGameEvent.Finished -> copy(sliderGameState = SliderGameState.Done(event.lastGameState))
    is SliderGameEvent.Restart,
    is SliderGameEvent.Start -> startingNewSliderGame()
}

private fun ServerState.startingNewSliderGame(): ServerState = copy(sliderGameState = newSliderGame(participants.map { it.apiKey }))

private fun newSliderGame(participants: List<ApiKey>): SliderGameState.InProgress =
    newSliderGame(participants, Random.nextDouble(1.0 - PegWidth * 3))

private fun newSliderGame(participants: List<ApiKey>, pegPosition: Double): SliderGameState.InProgress =
    SliderGameState.InProgress(
        participantStates = participants.associate {
            it.stringRepresentation to generateSequence {
                SliderState(gapOffset = Random.nextDouble(1.0 - SliderGapWidth * 3), position = 0.5)
            }.first { !it.letsThroughPegPositionedAt(pegPosition) }
        }.toSortedMap(),
        pegPosition = pegPosition,
        pegLevel = -1,
    )

internal fun SliderState.letsThroughPegPositionedAt(pegPosition: Double): Boolean =
    position in positionRangeInWhichPegWouldFallThrough(pegPosition)

internal fun SliderState.positionRangeInWhichPegWouldFallThrough(pegPosition: Double): ClosedFloatingPointRange<Double> =
    ((pegPosition - gapOffset + 1.0) / 2)
        .let { end -> (end - (SliderGapWidth - PegWidth) * 3 / 2)..end }
