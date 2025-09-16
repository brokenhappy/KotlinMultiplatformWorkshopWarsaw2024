@file:Suppress("ReplaceToWithInfixForm")

package kmpworkshop.server

import kmpworkshop.common.*
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds


@Serializable
sealed class ServerWideEvents : WorkshopEvent()
@Serializable
data class StageChangeEvent(val stage: WorkshopStage) : ServerWideEvents()
@Serializable
data class SettingsChangeEvent(val newSettings: ServerSettings) : ServerWideEvents()
@Serializable
data class RevertWholeStateEvent(val newState: ServerState) : ServerWideEvents()
@Serializable
data class RegistrationStartEvent(val name: String, val randomSeed: Long) : WorkshopEventWithResult<ApiKeyRegistrationResult>() {
    override fun applyWithResultTo(oldState: ServerState): Pair<ServerState, ApiKeyRegistrationResult> = when {
        !"[A-z 0-9]{1,20}".toRegex().matches(name) -> oldState to ApiKeyRegistrationResult.NameTooComplex
        oldState.participants.any { it.name == name } -> oldState to ApiKeyRegistrationResult.NameAlreadyExists
        else -> UUID.nameUUIDFromBytes(Random(randomSeed).nextBytes(16)).toString()
            .let { Participant(name, ApiKey(it)) }
            .let {
                oldState.copy(unverifiedParticipants = oldState.unverifiedParticipants + it) to ApiKeyRegistrationResult.Success(it.apiKey)
            }
    }
}

@Serializable
data class RegistrationVerificationEvent(val key: ApiKey) : WorkshopEventWithResult<NameVerificationResult>() {
    override fun applyWithResultTo(oldState: ServerState): Pair<ServerState, NameVerificationResult> {
        if (oldState.participants.any { it.apiKey == key })
            return oldState to NameVerificationResult.AlreadyRegistered
        val name = oldState
            .unverifiedParticipants
            .firstOrNull { it.apiKey == key }
            ?.name
            ?: return oldState to NameVerificationResult.ApiKeyDoesNotExist
        val stateWithoutUnverifiedParticipant = oldState.copy(
            unverifiedParticipants = oldState.unverifiedParticipants.filter { it.apiKey != key },
        )
        return if (oldState.participants.any { it.name == name })
            stateWithoutUnverifiedParticipant to NameVerificationResult.NameAlreadyExists
        else stateWithoutUnverifiedParticipant.copy(
            participants = stateWithoutUnverifiedParticipant.participants + Participant(name, key),
        ).scheduling(SoundPlayEvents.Success).after(0.seconds)
            .to(NameVerificationResult.Success)
    }
}

@Serializable
data class PuzzleFinishedEvent(val now: Instant, val participant: ApiKey, val puzzleName: String) : WorkshopEventWithResult<SolvingStatus>() {
    override fun applyWithResultTo(oldState: ServerState): Pair<ServerState, SolvingStatus> =
        (oldState.puzzleStates[puzzleName] as? PuzzleState.Opened)?.let { puzzleState ->
            when {
                participant.stringRepresentation in puzzleState.submissions -> oldState to SolvingStatus.AlreadySolved
                else -> oldState.copy(
                    puzzleStates = oldState.puzzleStates + puzzleName.to(
                        puzzleState.copy(
                            submissions = puzzleState.submissions + (participant.stringRepresentation to now)
                        )
                    )
                ).scheduling(SoundPlayEvents.Success).after(0.seconds)
                    .to(SolvingStatus.Done)
            }
        } ?: (oldState to SolvingStatus.PuzzleNotOpenedYet)
}

@Serializable
data class ParticipantDeactivationEvent(val participant: Participant) : ServerWideEvents()
@Serializable
data class ParticipantReactivationEvent(val participant: Participant, val randomSeed: Long) : ServerWideEvents()
@Serializable
data class ParticipantRemovalEvent(val participant: Participant) : ServerWideEvents()
@Serializable
data class ParticipantRejectionEvent(val participant: Participant) : ServerWideEvents()
@Serializable
data class ApplyScheduledEvent(val timedEvent: TimedEvent) : ServerWideEvents()

internal fun ServerState.after(event: ServerWideEvents): ServerState = when (event) {
    is StageChangeEvent -> copy(currentStage = event.stage)
    is SettingsChangeEvent -> copy(settings = event.newSettings)
    is ParticipantDeactivationEvent -> deactivateParticipant(event.participant)
    is ParticipantReactivationEvent -> with(Random(event.randomSeed)) { reactivateParticipant(event.participant) }
    is ParticipantRemovalEvent -> removeParticipant(event.participant)
    is ParticipantRejectionEvent -> copy(unverifiedParticipants = unverifiedParticipants - event.participant)
    is ApplyScheduledEvent -> copy(
        scheduledEvents = scheduledEvents - event.timedEvent,
    ).after(event.timedEvent.event)
    is RevertWholeStateEvent -> event.newState
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

context(_: Random)
private fun ServerState.reactivateParticipant(participant: Participant): ServerState = copy(
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
                ColorAndInstructionWithPrevious(randomColorAndInstruction(), randomColorAndInstruction()),
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
