package workshop.adminaccess

import kmpworkshop.common.ApiKey
import kmpworkshop.common.SlideResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

const val PegWidth = 0.075
const val SliderGapWidth = 0.1

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
data class SliderSuggestionEvent(
    val participant: ApiKey,
    val suggestedRatio: Double,
) : WorkshopEventWithResult<SlideResult>() {
    @Transient
    override val serializer: KSerializer<SlideResult> = kotlinx.serialization.serializer()

    override fun applyWithResultTo(oldState: ServerState): Pair<ServerState, SlideResult> =
        if (oldState.participantFor(participant) == null) oldState to SlideResult.InvalidApiKey
        else {
            (oldState.sliderGameState as? SliderGameState.InProgress)?.let { oldGameState ->
                oldGameState.moveSlider(participant, suggestedRatio.coerceIn(.0..1.0)).withGravityApplied()
                    .let {
                        oldState.copy(sliderGameState = it)
                            .applyIf({ it.sliderGameState is SliderGameState.Done }) {
                                it.scheduling(SoundPlayEvents.Success).after(0.seconds)
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

private fun SliderGameState.InProgress.findLevelOfParticipant(key: ApiKey): Int =
    participantStates.entries.indexOfFirst { it.key == key.stringRepresentation }

private fun SliderGameState.InProgress.findPositionOfParticipant(key: ApiKey): Double =
    participantStates.entries.first { it.key == key.stringRepresentation }.value.position

private fun SliderGameState.InProgress.withGravityApplied(): SliderGameState =
    participantStates.values.elementAtOrNull(pegLevel + 1)?.let { slider ->
        if (slider.letsThroughPegPositionedAt(pegPosition)) copy(pegLevel = pegLevel + 1).withGravityApplied()
        else this
    } ?: SliderGameState.Done(copy(pegLevel = participantStates.size))

fun ServerState.after(event: SliderGameEvent): ServerState = when (event) {
    is SliderGameEvent.Finished -> copy(sliderGameState = SliderGameState.Done(event.lastGameState))
    is SliderGameEvent.Restart -> with(Random(event.randomSeed)) { startingNewSliderGame() }
    is SliderGameEvent.Start -> with(Random(event.randomSeed)) { startingNewSliderGame() }
}

context(_: Random)
private fun ServerState.startingNewSliderGame(): ServerState =
    copy(sliderGameState = newSliderGame(participants.map { it.apiKey }))

context(random: Random)
private fun newSliderGame(participants: List<ApiKey>): SliderGameState.InProgress =
    newSliderGame(participants, random.nextDouble(1.0 - PegWidth * 3))

context(random: Random)
private fun newSliderGame(participants: List<ApiKey>, pegPosition: Double): SliderGameState.InProgress =
    SliderGameState.InProgress(
        participantStates = participants.associate {
            it.stringRepresentation to generateSequence {
                SliderState(gapOffset = random.nextDouble(1.0 - SliderGapWidth * 3), position = 0.5)
            }.first { !it.letsThroughPegPositionedAt(pegPosition) }
        }.toSortedMap(),
        pegPosition = pegPosition,
        pegLevel = -1,
    )

internal fun SliderState.letsThroughPegPositionedAt(pegPosition: Double): Boolean =
    position in positionRangeInWhichPegWouldFallThrough(pegPosition)

fun SliderState.positionRangeInWhichPegWouldFallThrough(pegPosition: Double): ClosedFloatingPointRange<Double> =
    ((pegPosition - gapOffset + 1.0) / 2)
        .let { end -> (end - (SliderGapWidth - PegWidth) * 3 / 2)..end }