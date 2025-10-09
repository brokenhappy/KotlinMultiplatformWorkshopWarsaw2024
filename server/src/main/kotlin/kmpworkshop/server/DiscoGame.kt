@file:OptIn(ExperimentalTime::class)

package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.DiscoGameInstruction
import kmpworkshop.common.SerializableColor
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
sealed class DiscoGameEvent : WorkshopEvent() {
    @Serializable
    data class StartFirst(val now: Instant, val randomSeed: Long) : DiscoGameEvent()
    @Serializable
    data class RestartFirst(val now: Instant, val randomSeed: Long) : DiscoGameEvent()
    @Serializable
    data class StopFirst(val now: Instant) : DiscoGameEvent()
    @Serializable
    data class StartSecond(val randomSeed: Long) : DiscoGameEvent()
    @Serializable
    data class RestartSecond(val randomSeed: Long) : DiscoGameEvent()
    @Serializable
    data object StopSecond : DiscoGameEvent()
    @Serializable
    data class GuessSubmissionEvent(val participant: ApiKey, val randomSeed: Long, val now: Instant) : DiscoGameEvent()
    @Serializable
    data class FirstTargetTick(val randomSeed: Long): DiscoGameEvent()
    @Serializable
    data class FirstParticipantTick(val randomSeed: Long): DiscoGameEvent()
    @Serializable
    data class SecondBackgroundTick(val randomSeed: Long): DiscoGameEvent()
    @Serializable
    data class SecondPressTimeout(val randomSeed: Long): DiscoGameEvent()
}

fun ServerState.after(event: DiscoGameEvent): ServerState = when (event) {
    is DiscoGameEvent.StopSecond -> copy(discoGameState = DiscoGameState.Second.Done)
    is DiscoGameEvent.RestartSecond -> with(Random(event.randomSeed)) { startingSecondDiscoGame() }
    is DiscoGameEvent.StartSecond -> with(Random(event.randomSeed)) { startingSecondDiscoGame() }
    is DiscoGameEvent.StopFirst -> copy(
        discoGameState = DiscoGameState.First.Done(
            submissions = (discoGameState as? DiscoGameState.First.InProgress)?.toSubmissionsIn(this)
                ?: (discoGameState as? DiscoGameState.First.Done)?.submissions
                ?: emptySubmissions(event.now),
        )
    )
    is DiscoGameEvent.RestartFirst -> with(Random(event.randomSeed)) { startingFirstDiscoGame(event.now) }
    is DiscoGameEvent.StartFirst -> with(Random(event.randomSeed)) { startingFirstDiscoGame(event.now) }
    is DiscoGameEvent.GuessSubmissionEvent -> with(Random(event.randomSeed)) { afterDiscoGameGuessSubmission(event.participant, event.now) }
    is DiscoGameEvent.SecondBackgroundTick -> Random(event.randomSeed).let { random ->
        when (val state = discoGameState) {
            is DiscoGameState.Second.Done,
            is DiscoGameState.First,
            is DiscoGameState.NotStarted -> this
            is DiscoGameState.Second.InProgress -> copy(
                discoGameState = state.copy(
                    orderedParticipants = state
                        .orderedParticipants
                        .map { it.copy(color = discoColors.random(random)) }
                ),
            ).schedulingSingle(DiscoGameEvent.SecondBackgroundTick(random.nextLong())).after(danceFloorChangeInterval)
        }
    }
    is DiscoGameEvent.SecondPressTimeout -> with(Random(event.randomSeed)) {
        when (val state = discoGameState) {
            is DiscoGameState.Second.Done,
            is DiscoGameState.First,
            is DiscoGameState.NotStarted -> this@after
            is DiscoGameState.Second.InProgress -> copy(discoGameState = state.restartingInstructions())
                .scheduling(DiscoGameEvent.SecondPressTimeout(nextLong())).after(secondDiscoGamePressTimeout)
        }
    }
    is DiscoGameEvent.FirstParticipantTick -> with(Random(event.randomSeed)) {
        when (val state = discoGameState) {
            is DiscoGameState.First.Done,
            is DiscoGameState.Second,
            is DiscoGameState.NotStarted -> this@after
            is DiscoGameState.First.InProgress -> copy(discoGameState = state.privateTick())
                .schedulingSingle(DiscoGameEvent.FirstParticipantTick(nextLong())).after(firstDiscoGamePrivateTickTimeout)
        }
    }
    is DiscoGameEvent.FirstTargetTick -> with(Random(event.randomSeed)) {
        when (val state = discoGameState) {
            is DiscoGameState.First.Done,
            is DiscoGameState.Second,
            is DiscoGameState.NotStarted -> this@after
            is DiscoGameState.First.InProgress -> copy(discoGameState = state.targetTick())
                .schedulingSingle(DiscoGameEvent.FirstTargetTick(nextLong())).after(firstDiscoGamePublicTickTimeout)
        }
    }
}

private fun emptySubmissions(now: Instant): Submissions =
    Submissions(startTime = now, participants = emptyList(), completedSubmissions = emptyMap())

context(random: Random)
fun ServerState.startingFirstDiscoGame(now: Instant): ServerState = copy(
    discoGameState = DiscoGameState.First.InProgress(
        states = participants.associate {
            it.apiKey.stringRepresentation to FirstDiscoGameParticipantState.InProgress(
                ColorAndInstructionWithPrevious(randomColorAndInstruction(), randomColorAndInstruction()),
                completionCount = 0,
            )
        },
        target = ColorAndInstructionWithPrevious(randomColorAndInstruction(), randomColorAndInstruction()),
        startTime = now,
    ),
)
    .schedulingSingle(DiscoGameEvent.FirstTargetTick(random.nextLong())).after(0.seconds)
    .schedulingSingle(DiscoGameEvent.FirstParticipantTick(random.nextLong())).after(0.seconds)

context(random: Random)
fun ServerState.startingSecondDiscoGame(): ServerState = copy(
    discoGameState = DiscoGameState.Second.InProgress(
        orderedParticipants = participants
            .map { SecondDiscoGameParticipantState(it.apiKey, SerializableColor(0, 0, 0)) }
            .shuffled(random),
        progress = 0,
        instructionOrder = emptyList(),
    ).restartingInstructions(),
)
    .schedulingSingle(DiscoGameEvent.SecondBackgroundTick(random.nextLong())).after(0.seconds)
    .schedulingSingle(DiscoGameEvent.SecondPressTimeout(random.nextLong())).after(1.5.seconds)

context(random: Random)
internal fun ServerState.afterDiscoGameGuessSubmission(participant: ApiKey, now: Instant): ServerState = when (val gameState = discoGameState) {
    is DiscoGameState.Second.Done,
    is DiscoGameState.First.Done,
    is DiscoGameState.NotStarted -> this
    is DiscoGameState.Second.InProgress ->
        if (gameState.currentParticipantThatShouldPress() == participant)
            if (gameState.progress >= gameState.orderedParticipants.lastIndex) copy(
                discoGameState = DiscoGameState.Second.Done
            ).scheduling(SoundPlayEvents.Success).after(0.seconds) else copy(
                discoGameState = gameState.copy(progress = gameState.progress + 1),
                scheduledEvents = scheduledEvents.filter { it.event !is DiscoGameEvent.SecondPressTimeout }
            )
                .scheduling(DiscoGameEvent.SecondPressTimeout(random.nextLong())).after(secondDiscoGamePressTimeout)
                .scheduling(SoundPlayEvents.Increment(gameState.progress.toDouble() / gameState.instructionOrder.size)).after(0.seconds)
        else copy(discoGameState = gameState.restartingInstructions())
            .applyIf({ gameState.progress > 0 }) { it.scheduling(SoundPlayEvents.ProgressLoss).after(0.seconds) }
    is DiscoGameState.First.InProgress -> {
        when (val participantState = gameState.states[participant.stringRepresentation]) {
            null,
            is FirstDiscoGameParticipantState.Done -> this
            is FirstDiscoGameParticipantState.InProgress -> if (participantState.pressedAtRightTimeIn(gameState)) {
                if (participantState.completionCount + 1 < numberOfCorrectGuessesToFinishThirdDiscoGame) copy(
                    discoGameState = gameState.copy(
                        states = gameState.states.put(participant, FirstDiscoGameParticipantState.InProgress(
                            participantState.colorAndInstructionState.nextRandom(),
                            completionCount = participantState.completionCount + 1,
                        ))
                    )
                ).scheduling(SoundPlayEvents.Increment((participantState.completionCount + 1).toDouble() / numberOfCorrectGuessesToFinishThirdDiscoGame)).after(0.seconds)
                else copy(
                    discoGameState = gameState.copy(
                        states = gameState.states.put(participant, FirstDiscoGameParticipantState.Done(now))
                    ).applyIf({ it.states.all { (_, state) -> state is FirstDiscoGameParticipantState.Done } }) {
                        DiscoGameState.First.Done(it.toSubmissionsIn(this))
                    }
                ).scheduling(SoundPlayEvents.Success).after(0.seconds)
            } else copy(
                discoGameState = gameState.copy(
                    states = gameState.states.put(participant, FirstDiscoGameParticipantState.InProgress(
                        participantState.colorAndInstructionState.nextRandom(),
                        completionCount = 0,
                    ))
                )
            ).applyIf({ participantState.completionCount > 0 }) {
                it.scheduling(SoundPlayEvents.ProgressLoss).after(0.seconds)
            }
        }
    }
}

private fun FirstDiscoGameParticipantState.InProgress.pressedAtRightTimeIn(gameState: DiscoGameState.First.InProgress) =
    gameState.target.current == colorAndInstructionState.current
        || gameState.target.current == colorAndInstructionState.previous
        || gameState.target.previous == colorAndInstructionState.current

private const val numberOfCorrectGuessesToFinishThirdDiscoGame = 4

private fun DiscoGameState.Second.InProgress.currentParticipantThatShouldPress(): ApiKey? =
    instructionOrder.getOrNull(progress)?.targetIn(this)

private fun DiscoGameInstructionRequest.targetIn(game: DiscoGameState.Second.InProgress): ApiKey? =
    participant.findPointIn(game)?.move(instruction)?.findParticipantIn(game)

internal fun DiscoGameState.First.InProgress.toSubmissionsIn(state: ServerState) = Submissions(
    startTime,
    participants = state.participants,
    completedSubmissions = states
        .mapNotNull { (key, state) ->
            (state as? FirstDiscoGameParticipantState.Done)?.let { ApiKey(key) to it.finishTime }
        }.toMap(),
)

private data class DiscoPoint(val x: Int, val y: Int)
private fun ApiKey.findPointIn(game: DiscoGameState.Second.InProgress): DiscoPoint? =
    game.orderedParticipants
        .indexOfFirst { it.participant == this }
        .takeUnless { it == -1 }
        ?.asOffsetToPointIn(game)
private fun DiscoPoint.findParticipantIn(game: DiscoGameState.Second.InProgress): ApiKey? =
    this.toOffsetIn(game)?.let { game.orderedParticipants.getOrNull(it) }?.participant
private fun DiscoPoint.toOffsetIn(game: DiscoGameState.Second.InProgress): Int? =
    if (y < 0 || x < 0 || x >= game.width) null else (y * game.width + x).takeIf { it < game.orderedParticipants.size }
private fun Int.asOffsetToPointIn(game: DiscoGameState.Second.InProgress): DiscoPoint =
    DiscoPoint(y = this / game.width, x = this % game.width)

internal val DiscoGameState.Second.InProgress.width: Int get() =
    orderedParticipants.size.widthOfNearestGreaterSquare() // TODO: Report bug if creating extension function in wrong file!

private fun Int.widthOfNearestGreaterSquare(): Int =
    (0..100).first { it * it >= this }

private fun DiscoPoint.move(instruction: DiscoGameInstruction): DiscoPoint =
    DiscoPoint(x + instruction.dx, y + instruction.dy)

context(random: Random)
internal fun DiscoGameState.Second.InProgress.restartingInstructions(): DiscoGameState.Second.InProgress = copy(
    progress = 0,
    instructionOrder = createSetOfInstructionsSoThatEachUserBothGetsToPressAsWellAsShowAnInstruction().shuffled(random)
)

context(_: Random)
private fun DiscoGameState.Second.InProgress.createSetOfInstructionsSoThatEachUserBothGetsToPressAsWellAsShowAnInstruction(): List<DiscoGameInstructionRequest> =
    createSetOfInstructionsThatInstructsFromEachUser()
        .let { instructions ->
            val targets = instructions.map { it.targetIn(this) }.toSet()
            instructions + createSetOfInstructionsThatTargetsEachUser().filter { it.targetIn(this) !in targets }
        }

context(_: Random)
private fun DiscoGameState.Second.InProgress.createSetOfInstructionsThatInstructsFromEachUser(): List<DiscoGameInstructionRequest> = orderedParticipants
    .map { it.participant }
    .map { participant -> createInstructionThatInstructsFrom(participant) }

context(random: Random)
internal fun DiscoGameState.Second.InProgress.createInstructionThatInstructsFrom(participant: ApiKey): DiscoGameInstructionRequest {
    val location = participant.findPointIn(this)!!
    return DiscoGameInstruction
        .entries
        .shuffled(random)
        .first { location.move(it).toOffsetIn(this).isNotNullAnd { it <= orderedParticipants.size } }
        .let { DiscoGameInstructionRequest(participant, it) }
}

context(_: Random)
private fun DiscoGameState.Second.InProgress.createSetOfInstructionsThatTargetsEachUser(): List<DiscoGameInstructionRequest> = orderedParticipants
    .map { it.participant }
    .map { participant -> createInstructionThatTargets(participant) }

context(random: Random)
internal fun DiscoGameState.Second.InProgress.createInstructionThatTargets(
    participant: ApiKey,
): DiscoGameInstructionRequest {
    val location = participant.findPointIn(this)!!
    return DiscoGameInstruction
        .entries
        .shuffled(random)
        .firstNotNullOf { instruction ->
            location
                .move(instruction.inverted())
                .findParticipantIn(this)
                ?.let { DiscoGameInstructionRequest(it, instruction) }
        }
}

internal val secondDiscoGamePressTimeout = 2.5.seconds
private val danceFloorChangeInterval = 0.8.seconds
private val firstDiscoGamePrivateTickTimeout = 1.5.seconds
private val firstDiscoGamePublicTickTimeout = 10.seconds

context(_: Random)
private fun DiscoGameState.First.InProgress.targetTick(): DiscoGameState = copy(target = target.nextRandom())

context(_: Random)
internal fun ColorAndInstructionWithPrevious.nextRandom() = ColorAndInstructionWithPrevious(
    current = randomColorAndInstruction(),
    previous = current,
)

context(random: Random)
private fun DiscoGameState.First.InProgress.privateTick(): DiscoGameState = copy(
    states = states.mapValues { (_, value) ->
        when (value) {
            is FirstDiscoGameParticipantState.Done -> value
            is FirstDiscoGameParticipantState.InProgress -> value.copy(
                colorAndInstructionState =
                    if (random.nextDouble() < .25) target
                    else value.colorAndInstructionState.nextRandom()
            )
        }
    },
)

context(random: Random)
fun randomColorAndInstruction(): ColorAndInstruction = ColorAndInstruction(
    color = discoColors.random(random),
    (DiscoGameInstruction.entries + null).random(random)
)


private val discoColors = listOf(
    SerializableColor(80, 80, 80),
    SerializableColor(255, 0, 0),
    SerializableColor(0, 255, 0),
    SerializableColor(0, 0, 255),
    SerializableColor(0, 255, 255),
    SerializableColor(255, 0, 255),
    SerializableColor(255, 255, 0),
    SerializableColor(255, 255, 255),
)

private fun DiscoGameInstruction.inverted(): DiscoGameInstruction =
    DiscoGameInstruction.entries.first { it.dx == -dx && it.dy == -dy }

inline fun <T> T?.isNotNullAnd(predicate: (T) -> Boolean): Boolean = this != null && predicate(this)
