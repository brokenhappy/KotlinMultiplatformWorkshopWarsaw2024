package kmpworkshop.server

import kmpworkshop.common.SerializableColor
import kmpworkshop.common.WorkshopStage
import kotlinx.serialization.Serializable
import kotlin.random.Random


@Serializable
sealed class ServerWideEvents : WorkshopEvent()
@Serializable
data class StageChangeEvent(val stage: WorkshopStage) : ServerWideEvents()
@Serializable
data class ParticipantDeactivationEvent(val participant: Participant) : ServerWideEvents()
@Serializable
data class ParticipantReactivationEvent(val participant: Participant, val randomSeed: Long) : ServerWideEvents()
@Serializable
data class ParticipantRemovalEvent(val participant: Participant) : ServerWideEvents()
@Serializable
data class ParticipantRejectionEvent(val participant: Participant) : ServerWideEvents()

internal fun ServerState.after(event: ServerWideEvents): ServerState = when (event) {
    is StageChangeEvent -> copy(currentStage = event.stage)
    is ParticipantDeactivationEvent -> deactivateParticipant(event.participant)
    is ParticipantReactivationEvent -> reactivateParticipant(event.participant, Random(event.randomSeed))
    is ParticipantRemovalEvent -> removeParticipant(event.participant)
    is ParticipantRejectionEvent -> copy(unverifiedParticipants = unverifiedParticipants - event.participant)
}

private fun ServerState.deactivateParticipant(participant: Participant): ServerState = copy(
    participants = participants - participant,
    deactivatedParticipants = deactivatedParticipants + participant,
    puzzleStates = puzzleStates.mapValues { (_, puzzleState) ->
        when (puzzleState) {
            PuzzleState.Unopened -> puzzleState
            is PuzzleState.Opened -> puzzleState
                .copy(submissions = puzzleState.submissions - participant.apiKey.stringRepresentation)
        }
    },
    sliderGameState = when (sliderGameState) {
        is SliderGameState.NotStarted -> sliderGameState
        is SliderGameState.InProgress -> sliderGameState.removeParticipant(participant)
        is SliderGameState.Done -> sliderGameState.copy(
            lastState = sliderGameState.lastState.removeParticipant(participant)
        )
    },
    discoGameState = when (discoGameState) {
        is DiscoGameState.Second.Done,
        is DiscoGameState.NotStarted -> discoGameState
        is DiscoGameState.Second.InProgress -> discoGameState.copy(
            orderedParticipants = discoGameState.orderedParticipants.filterNot { it.participant == participant.apiKey },
            instructionOrder = discoGameState.instructionOrder.filterNot { it.participant == participant.apiKey },
        )
        is DiscoGameState.First.Done -> discoGameState
        is DiscoGameState.First.InProgress -> discoGameState.copy(
            states = discoGameState.states - participant.apiKey.stringRepresentation,
        )
    },
)

private fun ServerState.reactivateParticipant(participant: Participant, random: Random): ServerState = copy(
    participants = participants + participant,
    deactivatedParticipants = deactivatedParticipants - participant,
    discoGameState = when (val gameState = discoGameState) {
        is DiscoGameState.Second.Done,
        is DiscoGameState.NotStarted -> gameState
        is DiscoGameState.Second.InProgress -> gameState.copy(
            orderedParticipants = gameState.orderedParticipants
                + SecondDiscoGameParticipantState(participant.apiKey, color = SerializableColor(0, 0, 0)),
        ).let {
            it.copy(
                instructionOrder = it.instructionOrder
                    + it.createInstructionThatInstructsFrom(participant.apiKey)
                    + it.createInstructionThatTargets(participant.apiKey)
            )
        }
        is DiscoGameState.First.Done -> discoGameState
        is DiscoGameState.First.InProgress -> gameState.copy(
            states = gameState.states.put(participant.apiKey, FirstDiscoGameParticipantState.InProgress(
                ColorAndInstructionWithPrevious(randomColorAndInstruction(random), randomColorAndInstruction(random)),
                completionCount = 0,
            )),
        )
    },
)

private fun SliderGameState.InProgress.removeParticipant(participant: Participant): SliderGameState.InProgress = copy(
    participantStates = participantStates - participant.apiKey.stringRepresentation
)

private fun ServerState.removeParticipant(participant: Participant): ServerState = copy(
    deactivatedParticipants = deactivatedParticipants - participant,
)
