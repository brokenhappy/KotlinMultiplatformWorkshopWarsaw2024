@file:Suppress("ReplaceToWithInfixForm")
@file:OptIn(ExperimentalTime::class)

package workshop.adminaccess

import kmpworkshop.common.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.*
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant


@Serializable
sealed class ServerWideEvents : WorkshopEvent()
@Serializable
data class StageChangeEvent(val stage: WorkshopStage) : ServerWideEvents()
@Serializable
data class SettingsChangeEvent(val newSettings: ServerSettings) : ServerWideEvents()
@Serializable
data class RevertWholeStateEvent(val newState: ServerState) : ServerWideEvents()
@Serializable
data class RegistrationStartEvent(
    val name: String,
    val randomSeed: Long,
) : WorkshopEventWithResult<ApiKeyRegistrationResult>() {
    @Transient
    override val serializer: KSerializer<ApiKeyRegistrationResult> = kotlinx.serialization.serializer()

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
data class RegistrationVerificationEvent(
    val key: ApiKey,
) : WorkshopEventWithResult<NameVerificationResult>() {
    @Transient
    override val serializer: KSerializer<NameVerificationResult> = kotlinx.serialization.serializer()

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
            tables = stateWithoutUnverifiedParticipant.tables + Table(0, 0, key),
        ).scheduling(SoundPlayEvent.Success)
            .after(0.seconds)
            .to(NameVerificationResult.Success)
    }
}

@Serializable
data class PuzzleFinishedEvent(
    val now: Instant,
    val participant: ApiKey,
    val puzzleId: String,
) : WorkshopEventWithResult<PuzzleCompletionResult>() {
    @Transient
    override val serializer: KSerializer<PuzzleCompletionResult> = kotlinx.serialization.serializer()

    override fun applyWithResultTo(oldState: ServerState): Pair<ServerState, PuzzleCompletionResult> =
        (oldState.puzzleStates[puzzleId] as? PuzzleState.Opened)?.let { puzzleState ->
            when {
                participant.stringRepresentation in puzzleState.submissions -> oldState to PuzzleCompletionResult.AlreadySolved
                else -> oldState.copy(
                    puzzleStates = oldState.puzzleStates + puzzleId.to(
                        puzzleState.copy(
                            submissions = puzzleState.submissions + (participant.stringRepresentation to now)
                        )
                    )
                ).scheduling(SoundPlayEvent.Success).after(0.seconds)
                    .to(PuzzleCompletionResult.Done)
            }
        } ?: (oldState to PuzzleCompletionResult.PuzzleNotOpenedYet)
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
@Serializable
data class TableAdded(val table: Table) : ServerWideEvents()
@Serializable
data class TableRemoved(val table: Table) : ServerWideEvents()
@Serializable
data class TeamChanged(val apiKey: ApiKey, val newTeam: TeamColor) : ServerWideEvents()

internal fun ServerState.after(
    event: ServerWideEvents,
    onSoundEvent: (SoundPlayEvent) -> Unit,
): ServerState = when (event) {
    is StageChangeEvent -> copy(currentStage = event.stage)
    is SettingsChangeEvent -> copy(settings = event.newSettings)
    is ParticipantDeactivationEvent -> deactivateParticipant(event.participant)
    is ParticipantReactivationEvent -> with(Random(event.randomSeed)) { reactivateParticipant(event.participant) }
    is ParticipantRemovalEvent -> removeParticipant(event.participant)
    is ParticipantRejectionEvent -> copy(unverifiedParticipants = unverifiedParticipants - event.participant)
    is ApplyScheduledEvent -> copy(
        scheduledEvents = scheduledEvents - event.timedEvent,
    ).after(event.timedEvent.event, onSoundEvent = onSoundEvent)
    is RevertWholeStateEvent -> event.newState
    is TeamAssignmentChange -> copy(
        participants = participants
            .firstOrNull { it.apiKey == event.apiKey }
            ?.let { participants - it + it.copy(team = event.team) }
            ?: participants
    )
    is AddTeam -> applyIf(teamCount < TeamColor.entries.size) { copy(teamCount = teamCount + 1) }
    is RemoveTeam -> applyIf(teamCount > 2) { copy(teamCount = teamCount - 1).redistributeRemovedTeam() }
    is TableAdded -> when {
        tables.any { it.x == event.table.x && it.y == event.table.y } ->
            this.after(TableAdded(event.table.copy(x = event.table.x + 1, y = event.table.y + 1)), onSoundEvent)
        event.table.assignee == null -> tables
            .map { it.assignee }
            .toSet()
            .let { allAssignedKeys -> participants.firstOrNull { it.apiKey !in allAssignedKeys } }
            ?.let { this.after(TableAdded(event.table.copy(assignee = it.apiKey)), onSoundEvent) }
            ?: copy(tables = tables + event.table)
        else -> copy(tables = tables + event.table)
    }
    is TableRemoved -> copy(tables = tables - event.table)
    is TeamChanged -> copy(
        participants = participants.map {
            if (it.apiKey == event.apiKey) it.copy(team = event.newTeam) else it
        },
        deactivatedParticipants = deactivatedParticipants.map {
            if (it.apiKey == event.apiKey) it.copy(team = event.newTeam) else it
        },
    )
}

private fun ServerState.redistributeRemovedTeam(): ServerState {
    val (teamIsOkay, needsNewTeam) = participants.partition { it.team.ordinal < teamCount }
    return copy(participants = teamIsOkay + needsNewTeam.mapIndexed { i, oldParticipant ->
        oldParticipant.copy(team = TeamColor.entries[i % teamCount])
    })
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
)

context(_: Random)
private fun ServerState.reactivateParticipant(participant: Participant): ServerState = copy(
    participants = participants + participant,
    deactivatedParticipants = deactivatedParticipants - participant,
)

private fun ServerState.removeParticipant(participant: Participant): ServerState = copy(
    deactivatedParticipants = deactivatedParticipants - participant,
    tables = tables.map { if (it.assignee == participant) it.copy(assignee = null) else it }
)
