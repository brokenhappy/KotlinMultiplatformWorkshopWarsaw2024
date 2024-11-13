@file:Suppress("ReplaceToWithInfixForm")

package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.SlideResult
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

@Serializable
sealed class SliderGameEvent : WorkshopEvent() {
    @Serializable
    data class Finished(val lastGameState: SliderGameState.InProgress) : SliderGameEvent()
    @Serializable
    data class Start(val randomSeed: Long) : SliderGameEvent()
    @Serializable
    data class Restart(val randomSeed: Long) : SliderGameEvent()
}

@Serializable
data class SliderSuggestionEvent(val participant: ApiKey, val suggestedRatio: Double) : WorkshopEventWithResult<SlideResult>() {
    override fun applyWithResultTo(oldState: ServerState): Pair<ServerState, SlideResult> =
        if (oldState.participantFor(participant) == null) oldState to SlideResult.InvalidApiKey
        else {
            (oldState.sliderGameState as? SliderGameState.InProgress)?.let { oldGameState ->
                oldGameState.moveSlider(participant, suggestedRatio.coerceIn(.0..1.0)).withGravityApplied()
                    .let {
                        oldState.copy(sliderGameState = it)
                            .applyIf({ it.sliderGameState is SliderGameState.Done }) {
                                it.scheduling(TimedEventType.PlaySuccessSound).after(0.seconds)
                            }
                            .to(SlideResult.Success(when (it) {
                                is SliderGameState.InProgress -> it.findPositionOfParticipant(participant)
                                is SliderGameState.Done -> it
                                    .lastState
                                    .findPositionOfParticipant(participant)
                                is SliderGameState.NotStarted -> error("Impossible")
                            }))
                    }
            } ?: oldState.to(SlideResult.NoSliderGameInProgress)
        }

}

internal fun ServerState.participantFor(apiKey: ApiKey) = participants.firstOrNull { it.apiKey == apiKey }

private fun SliderGameState.InProgress.withGravityApplied(): SliderGameState =
    participantStates.values.elementAtOrNull(pegLevel + 1)?.let { slider ->
        if (slider.letsThroughPegPositionedAt(pegPosition)) copy(pegLevel = pegLevel + 1).withGravityApplied()
        else this
    } ?: SliderGameState.Done(copy(pegLevel = participantStates.size))

private fun SliderGameState.InProgress.findLevelOfParticipant(key: ApiKey): Int =
    participantStates.entries.indexOfFirst { it.key == key.stringRepresentation }

private fun SliderGameState.InProgress.findPositionOfParticipant(key: ApiKey): Double =
    participantStates.entries.first { it.key == key.stringRepresentation }.value.position

private fun SliderGameState.InProgress.moveSlider(key: ApiKey, ratio: Double): SliderGameState.InProgress {
    val sliderState = participantStates[key.stringRepresentation]!!
    return copy(
        participantStates = (participantStates + key.stringRepresentation.to(
            sliderState.copy(
                position = if (findLevelOfParticipant(key) == pegLevel) {
                    ratio.coerceIn(sliderState.positionRangeInWhichPegWouldFallThrough(pegPosition))
                } else ratio
            )
        )).toSortedMap()
    )
}

fun ServerState.after(event: SliderGameEvent): ServerState = when (event) {
    is SliderGameEvent.Finished -> copy(sliderGameState = SliderGameState.Done(event.lastGameState))
    is SliderGameEvent.Restart -> with(Random(event.randomSeed)) { startingNewSliderGame() }
    is SliderGameEvent.Start -> with(Random(event.randomSeed)) { startingNewSliderGame() }
}

context(Random)
private fun ServerState.startingNewSliderGame(): ServerState =
    copy(sliderGameState = newSliderGame(participants.map { it.apiKey }))

context(Random)
private fun newSliderGame(participants: List<ApiKey>): SliderGameState.InProgress =
    newSliderGame(participants, nextDouble(1.0 - PegWidth * 3))

context(Random)
private fun newSliderGame(participants: List<ApiKey>, pegPosition: Double): SliderGameState.InProgress =
    SliderGameState.InProgress(
        participantStates = participants.associate {
            it.stringRepresentation to generateSequence {
                SliderState(gapOffset = nextDouble(1.0 - SliderGapWidth * 3), position = 0.5)
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
