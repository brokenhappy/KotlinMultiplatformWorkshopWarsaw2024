package kmpworkshop.server

import kotlinx.serialization.Serializable


@Serializable
sealed class ServerWideEvents : WorkshopEvent()
@Serializable
data class StageChangeEvent(val stage: WorkshopStage) : ServerWideEvents()
@Serializable
data class ParticipantDeactivationEvent(val participant: Participant) : ServerWideEvents()
@Serializable
data class ParticipantReactivationEvent(val participant: Participant) : ServerWideEvents()
@Serializable
data class ParticipantRemovalEvent(val participant: Participant) : ServerWideEvents()
@Serializable
data class ParticipantRejectionEvent(val participant: Participant) : ServerWideEvents()

internal fun ServerState.after(event: ServerWideEvents): ServerState = when (event) {
    is StageChangeEvent -> copy(currentStage = event.stage)
    is ParticipantDeactivationEvent -> deactivateParticipant(event.participant)
    is ParticipantReactivationEvent -> reactivateParticipant(event.participant)
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
        DiscoGameState.Done,
        DiscoGameState.NotStarted -> discoGameState
        is DiscoGameState.InProgress -> discoGameState.copy(
            orderedParticipants = discoGameState.orderedParticipants.filterNot { it.participant == participant.apiKey },
            instructionOrder = discoGameState.instructionOrder.filterNot { it.participant == participant.apiKey },
        )
    },
)

private fun ServerState.reactivateParticipant(participant: Participant): ServerState = copy(
    participants = participants + participant,
    deactivatedParticipants = deactivatedParticipants - participant,
    discoGameState = when (val gameState = discoGameState) {
        DiscoGameState.Done,
        DiscoGameState.NotStarted -> gameState
        is DiscoGameState.InProgress -> gameState.copy(
            orderedParticipants = gameState.orderedParticipants
                + DiscoGameParticipantState(participant.apiKey, color = kmpworkshop.common.Color(0, 0, 0)),
        ).let {
            it.copy(
                instructionOrder = it.instructionOrder
                    + it.createInstructionThatInstructsFrom(participant.apiKey)
                    + it.createInstructionThatTargets(participant.apiKey)
            )
        }
    },
)

private fun SliderGameState.InProgress.removeParticipant(participant: Participant): SliderGameState.InProgress = copy(
    participantStates = participantStates - participant.apiKey.stringRepresentation
)

private fun ServerState.removeParticipant(participant: Participant): ServerState = copy(
    deactivatedParticipants = deactivatedParticipants - participant,
)
