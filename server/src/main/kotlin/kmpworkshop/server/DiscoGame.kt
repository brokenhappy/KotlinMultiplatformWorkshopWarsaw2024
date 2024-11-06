package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.DiscoGameInstruction

internal fun ServerState.afterDiscoGameKeyPressBy(participant: ApiKey): ServerState =
    when (val gameState = discoGameState) {
        DiscoGameState.Done,
        DiscoGameState.NotStarted -> this
        is DiscoGameState.InProgress ->
            if (gameState.currentParticipantThatShouldPress() == participant)
                if (gameState.progress >= gameState.orderedParticipants.lastIndex)
                    copy(discoGameState = DiscoGameState.Done)
                else copy(
                    discoGameState = gameState.copy(progress = gameState.progress + 1),
                    scheduledEvents = scheduledEvents.filter { it.type !is TimedEventType.DiscoGamePressTimeoutEvent }
                ).scheduling(TimedEventType.DiscoGamePressTimeoutEvent).after(discoGamePressTimeout)
            else copy(discoGameState = gameState.restartingInstructions())
    }

private fun DiscoGameState.InProgress.currentParticipantThatShouldPress(): ApiKey? =
    instructionOrder.getOrNull(progress)?.targetIn(this)

private fun DiscoGameInstructionRequest.targetIn(game: DiscoGameState.InProgress): ApiKey? =
    participant.findPointIn(game)?.move(instruction)?.findParticipantIn(game)

private data class DiscoPoint(val x: Int, val y: Int)
private fun ApiKey.findPointIn(game: DiscoGameState.InProgress): DiscoPoint? =
    game.orderedParticipants
        .indexOfFirst { it.participant == this }
        .takeUnless { it == -1 }
        ?.asOffsetToPointIn(game)
private fun DiscoPoint.findParticipantIn(game: DiscoGameState.InProgress): ApiKey? =
    this.toOffsetIn(game)?.let { game.orderedParticipants.getOrNull(it) }?.participant
private fun DiscoPoint.toOffsetIn(game: DiscoGameState.InProgress): Int? =
    if (y < 0 || x < 0 || x >= game.width) null else (y * game.width + x).takeIf { it < game.orderedParticipants.size }
private fun Int.asOffsetToPointIn(game: DiscoGameState.InProgress): DiscoPoint =
    DiscoPoint(y = this / game.width, x = this % game.width)

internal val DiscoGameState.InProgress.width: Int get() =
    orderedParticipants.size.widthOfNearestGreaterSquare() // TODO: Report bug if creating extension function in wrong file!

private fun Int.widthOfNearestGreaterSquare(): Int =
    (0..100).first { it * it >= this }

private fun DiscoPoint.move(instruction: DiscoGameInstruction): DiscoPoint =
    DiscoPoint(x + instruction.dx, y + instruction.dy)

internal fun DiscoGameState.InProgress.restartingInstructions(): DiscoGameState.InProgress = copy(
    progress = 0,
    instructionOrder = createSetOfInstructionsSoThatEachUserBothGetsToPressAsWellAsShowAnInstruction().shuffled()
)

private fun DiscoGameState.InProgress.createSetOfInstructionsSoThatEachUserBothGetsToPressAsWellAsShowAnInstruction(): List<DiscoGameInstructionRequest> =
    createSetOfInstructionsThatInstructsFromEachUser()
        .let { instructions ->
            val targets = instructions.map { it.targetIn(this) }.toSet()
            instructions + createSetOfInstructionsThatTargetsEachUser().filter { it.targetIn(this) !in targets }
        }

private fun DiscoGameState.InProgress.createSetOfInstructionsThatInstructsFromEachUser(): List<DiscoGameInstructionRequest> = orderedParticipants
    .map { it.participant }
    .map { participant -> createInstructionThatInstructsFrom(participant) }

internal fun DiscoGameState.InProgress.createInstructionThatInstructsFrom(participant: ApiKey): DiscoGameInstructionRequest {
    val location = participant.findPointIn(this)!!
    return DiscoGameInstruction
        .entries
        .shuffled()
        .first { location.move(it).toOffsetIn(this).isNotNullAnd { it <= orderedParticipants.size } }
        .let { DiscoGameInstructionRequest(participant, it) }
}

private fun DiscoGameState.InProgress.createSetOfInstructionsThatTargetsEachUser(): List<DiscoGameInstructionRequest> = orderedParticipants
    .map { it.participant }
    .map { participant -> createInstructionThatTargets(participant) }

internal fun DiscoGameState.InProgress.createInstructionThatTargets(participant: ApiKey): DiscoGameInstructionRequest {
    val location = participant.findPointIn(this)!!
    return DiscoGameInstruction
        .entries
        .shuffled()
        .firstNotNullOf { instruction ->
            location
                .move(instruction.inverted())
                .findParticipantIn(this)
                ?.let { DiscoGameInstructionRequest(it, instruction) }
        }
}

private fun DiscoGameInstruction.inverted(): DiscoGameInstruction =
    DiscoGameInstruction.entries.first { it.dx == -dx && it.dy == -dy }

private inline fun <T> T?.isNotNullAnd(predicate: (T) -> Boolean): Boolean = this != null && predicate(this)
