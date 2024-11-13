package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.PressiveGamePressType
import kmpworkshop.server.PressivePairingState.SuccessFullyPaired
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

@Serializable
sealed class PressiveGameEvent : WorkshopEvent() {
    @Serializable
    data class StartFirst(val now: Instant, val randomSeed: Long) : PressiveGameEvent()
    @Serializable
    data class StartSecond(val randomSeed: Long) : PressiveGameEvent()
    @Serializable
    data class StartThird(val randomSeed: Long) : PressiveGameEvent()
}

@Serializable
data class PressiveGamePressEvent(val now: Instant, val randomSeed: Long, val participant: ApiKey, val pressEvent: PressiveGamePressType) : WorkshopEventWithResult<String?>() {
    override fun applyWithResultTo(oldState: ServerState): Pair<ServerState, String?> =
        oldState.pressiveGameState.pressing(pressEvent, presserKey = participant, now, Random(randomSeed))
            .let { update ->
                when (update) {
                    is PressiveGamePressResult.Hint -> oldState to update.message
                    is PressiveGamePressResult.Update -> oldState.copy(pressiveGameState = update.newState) to null
                    is PressiveGamePressResult.UpdateWithEvent -> oldState.copy(
                        pressiveGameState = update.update.newState,
                    ).scheduling(update.event).after(0.seconds) to null
                }
            }
}

fun ServerState.after(event: PressiveGameEvent): ServerState = when (event) {
    is PressiveGameEvent.StartFirst -> startingFirstPressiveGame(event.now, Random(event.randomSeed))
    is PressiveGameEvent.StartSecond -> startingSecondPressiveGame(Random(event.randomSeed))
    is PressiveGameEvent.StartThird -> startingThirdPressiveGame(Random(event.randomSeed))
}

private fun ServerState.startingFirstPressiveGame(now: Instant, random: Random): ServerState = copy(
    pressiveGameState = PressiveGameState.FirstGameInProgress(
        startTime = now,
        states = participants.associate { participant ->
            Pair(
                participant.apiKey.stringRepresentation,
                newFirstPressiveGameState(justFailed = false, random),
            )
        },
    ),
)

private fun ServerState.startingSecondPressiveGame(random: Random): ServerState =
    copy(pressiveGameState = PressiveGameState.SecondGameInProgress(
        order = participants.shuffled(random).map { it.apiKey },
        progress = 0,
        states = participants
            .zip(
                binaryMoreCodeIdentifiers(count = participants.size)
            )
            .associate { (participant, code) ->
                Pair(
                    participant.apiKey.stringRepresentation,
                    SecondPressiveGameParticipantState(
                        PressivePairingState.InProgress(""),
                        participant.apiKey,
                        personalId = code,
                        isBeingCalled = false,
                    ),
                )
            }
    ))

private fun ServerState.startingThirdPressiveGame(random: Random): ServerState =
    copy(
        pressiveGameState = PressiveGameState.ThirdGameInProgress(
            order = participants.shuffled(random).map { it.apiKey },
            progress = 0,
            participantThatIsBeingRung = null,
        )
    ).scheduling(TimedEventType.PressiveGameTickEvent).after(0.seconds)

internal fun SecondPressiveGameParticipantState.toHint(state: ServerState): String = """
    ${if (isBeingCalled) "|Someone is calling you, try to call them back!\n" else ""}|You are: $personalId
    |${
        when (pairingState) {
            is PressivePairingState.InProgress -> "Dialing: ${pairingState.progress}"
            PressivePairingState.TriedToCallNonExistingCode -> "This sequence does not exist! Try again!"
            PressivePairingState.PartnerHungUp -> "Oh no! Your partner just reset! Let's start pairing again!"
            is SuccessFullyPaired -> "You are now paired! Now you wait for all others to pair!"
            is PressivePairingState.Calling -> "You are ringing: ${state.participants.first { it -> it.apiKey == pairingState.partner }.name}, wait for them to call back!"
            PressivePairingState.DialedPersonIsBeingCalled -> "The person you dialed is already being called! Let's try someone else!"
            PressivePairingState.DialedPersonIsCalling -> "The person you dialed is already calling! Ask them to reset or try someone else!"
            PressivePairingState.DialedThemselves -> "You dialed yourself! Try calling someone else!"
            is PressivePairingState.RoundSuccess -> """
                Your position in the sequence comes ${if (pairingState.isPlacedBeforePartner) "before" else "after"} the person you called!
                |Continue dialing to get more information, or try pressing the correct sequence!
            """.trimMargin()
        }
    }
    |Do a double press to restart dialing!
""".trimMargin()

internal fun FirstPressiveGameParticipantState.toHint(): String = when {
    pressesLeft.isEmpty() -> "Yay! You've finished this puzzle, you can now help the others!"
    pressesLeft.size == NumberOfPressesNeededForFirstPressiveGame -> when {
        this.justFailed -> """
            That was the wrong button!
            Let's start gain by doing a ${pressesLeft.first().asUserReadableString()}
        """.trimIndent()
        else -> """
            Welcome to the Pressive Game!
            Start by doing a ${pressesLeft.first().asUserReadableString()}
        """.trimIndent()
    }
    else -> listOf("Woohoo", "Awesome", "Nice", "Well Done", "Great Job").let { cheers ->
        """
            ${cheers[pressesLeft.size % cheers.size]}!
            Next up: Do a ${pressesLeft.first().asUserReadableString()}
        """.trimIndent()
    }
}

private const val NumberOfPressesNeededForFirstPressiveGame = 4

sealed class PressiveGamePressResult {
    data class Hint(val message: String): PressiveGamePressResult()
    data class Update(val newState: PressiveGameState): PressiveGamePressResult()
    data class UpdateWithEvent(val update: Update, val event: TimedEventType): PressiveGamePressResult()
}

fun PressiveGamePressResult.map(mapper: (PressiveGameState) -> PressiveGameState): PressiveGamePressResult = when (this) {
    is PressiveGamePressResult.Hint -> this
    is PressiveGamePressResult.Update -> copy(newState = mapper(newState))
    is PressiveGamePressResult.UpdateWithEvent -> copy(update = update.map(mapper) as PressiveGamePressResult.Update)
}

fun PressiveGameState.toPressResult(): PressiveGamePressResult.Update = PressiveGamePressResult.Update(this)

fun PressiveGameState.withEvent(event: TimedEventType): PressiveGamePressResult.UpdateWithEvent =
    PressiveGamePressResult.UpdateWithEvent(toPressResult(), event)

internal fun PressiveGameState.pressing(
    type: PressiveGamePressType,
    presserKey: ApiKey,
    now: Instant,
    random: Random,
): PressiveGamePressResult = when (this) {
    PressiveGameState.NotStarted -> PressiveGamePressResult.Hint("Hold up there fella! We haven't started the game yet :)")
    PressiveGameState.ThirdGameDone,
    PressiveGameState.SecondGameDone,
    is PressiveGameState.FirstGameDone -> PressiveGamePressResult.Hint(
        "I know you're excited, but you're gonna have to wait until we start the next round!\nPerhaps you could help your peers so we are ready faster?"
    )
    is PressiveGameState.FirstGameInProgress -> states[presserKey]?.let { state ->
        when (type) {
            state.pressesLeft.first() ->
                copy(states = states.put(presserKey, state.dropSinglePressLeft(now)))
                    .let { newState ->
                        // Mind the early return! We only finish the game if...
                        PressiveGameState.FirstGameDone(
                            startTime = newState.startTime,
                            finishTimes = newState.states.mapValues { (_, participantState) ->
                                participantState.finishTime ?: return@let newState // ...All the participants finished
                            },
                        )
                    }

            else -> copy(states = states.put(presserKey, newFirstPressiveGameState(justFailed = true, random)))
        }
    }?.toPressResult() ?: PressiveGamePressResult.Hint(
        "You somehow are not part of this Pressive round! Contact the workshop host for help!"
    )
    is PressiveGameState.SecondGameInProgress -> states[presserKey]?.let { presserState ->
        val stateWithProgressIncreasedAndSound =
            if (order[progress] == presserKey) copy(progress = progress + 1)
                .withEvent(TimedEventType.PlayIncrementSound(progress.toDouble() / order.size))
            else if (progress > 0) copy(progress = 0).withEvent(TimedEventType.PlayProgressLossSound)
            else copy(progress = 0).toPressResult()
        stateWithProgressIncreasedAndSound.map { stateWithProgressIncreased ->
            stateWithProgressIncreased as PressiveGameState.SecondGameInProgress
            if (stateWithProgressIncreased.progress == order.size) PressiveGameState.SecondGameDone // TODO: Sound effects!
            else when (presserState.pairingState) {
                is PressivePairingState.InProgress,
                PressivePairingState.DialedThemselves,
                PressivePairingState.DialedPersonIsBeingCalled,
                PressivePairingState.DialedPersonIsCalling,
                PressivePairingState.PartnerHungUp,
                is PressivePairingState.RoundSuccess,
                PressivePairingState.TriedToCallNonExistingCode ->
                    stateWithProgressIncreased.updatedStatesAfterButtonPress(type, presserState)
                is SuccessFullyPaired,
                is PressivePairingState.Calling -> when (type) {
                    PressiveGamePressType.DoublePress -> presserState
                        .pairingState
                        .let { (it as? SuccessFullyPaired)?.partner ?: (it as? PressivePairingState.Calling)?.partner!! }
                        .let { partner -> resetting(presserState).hangUp(partner) }
                    else -> stateWithProgressIncreased
                }
            }
        }
    } ?: PressiveGamePressResult.Hint(
        "You somehow are not part of this Pressive round! Contact the workshop host for help!"
    )
    is PressiveGameState.ThirdGameInProgress ->
        (if (order[progress] == presserKey) progress + 1 else 0).let { nextProgress ->
            copy(progress = nextProgress).let {
                if (it.progress == order.size)
                    PressiveGameState.ThirdGameDone.withEvent(TimedEventType.PlaySuccessSound)
                else if (nextProgress > 0)
                    it.withEvent(TimedEventType.PlayIncrementSound(nextProgress.toDouble() / order.size))
                else if (progress > 0)
                    it.withEvent(TimedEventType.PlayProgressLossSound)
                else it.toPressResult()
            }
        }
}

internal fun newFirstPressiveGameState(justFailed: Boolean, random: Random): FirstPressiveGameParticipantState =
    FirstPressiveGameParticipantState(newRandomPresses(random), justFailed = justFailed, finishTime = null)

private fun FirstPressiveGameParticipantState.dropSinglePressLeft(now: Instant): FirstPressiveGameParticipantState =
    copy(pressesLeft = pressesLeft.drop(1))
        .let { if (it.pressesLeft.isEmpty()) it.copy(finishTime = now) else it }

private fun PressiveGameState.SecondGameInProgress.updatedStatesAfterButtonPress(
    pressType: PressiveGamePressType,
    presser: SecondPressiveGameParticipantState
): PressiveGameState.SecondGameInProgress = when (pressType) {
    PressiveGamePressType.SinglePress,
    PressiveGamePressType.LongPress ->
        copy(states = checkWhetherCallWasMade(progressAfterTypingNextChar = presser.pressing(pressType), presser))
    PressiveGamePressType.DoublePress -> resetting(presser)
}

private fun PressiveGameState.SecondGameInProgress.resetting(
    resetter: SecondPressiveGameParticipantState
): PressiveGameState.SecondGameInProgress =
    copy(states = states.put(resetter.key, resetter.copy(pairingState = PressivePairingState.InProgress(""))))

private fun SecondPressiveGameParticipantState.pressing(
    pressType: PressiveGamePressType
): PressivePairingState.InProgress = PressivePairingState.InProgress(
    ((pairingState as? PressivePairingState.InProgress)?.progress ?: "") + when (pressType) {
        PressiveGamePressType.SinglePress -> "."
        PressiveGamePressType.LongPress -> "-"
        else -> error("Impossible")
    },
)

private fun PressiveGameState.SecondGameInProgress.checkWhetherCallWasMade(
    progressAfterTypingNextChar: PressivePairingState.InProgress,
    presser: SecondPressiveGameParticipantState
): Map<ApiKeyString, SecondPressiveGameParticipantState> =
    if (progressAfterTypingNextChar.progress.length == presser.personalId.length) {
        when (progressAfterTypingNextChar.progress) {
            presser.personalId -> states
                .put(presser.key, presser.copy(pairingState = PressivePairingState.DialedThemselves))
            else -> states.values.firstOrNull { it.personalId == progressAfterTypingNextChar.progress }
                ?.let { other -> updatedStatesAfterCallMadeBy(presser, other) }
                ?: states.put(presser.key, presser.copy(pairingState = PressivePairingState.TriedToCallNonExistingCode))
        }
    } else states.put(presser.key, presser.copy(pairingState = progressAfterTypingNextChar))

private fun PressiveGameState.SecondGameInProgress.updatedStatesAfterCallMadeBy(
    caller: SecondPressiveGameParticipantState,
    callee: SecondPressiveGameParticipantState
): Map<ApiKeyString, SecondPressiveGameParticipantState> = when {
    callee.isBeingCalled -> states
        .put(caller.key, caller.copy(pairingState = PressivePairingState.DialedPersonIsBeingCalled))
    callee.pairingState is PressivePairingState.Calling || callee.pairingState is SuccessFullyPaired -> when {
        (callee.pairingState as? PressivePairingState.Calling)?.partner == caller.key -> states
            .put(caller.key, caller.successfullyPairedWith(callee.key))
            .put(callee.key, callee.successfullyPairedWith(caller.key))
            .tryToFinishRound(compareBy { order.indexOf(it) })
        else -> states
            .put(caller.key, caller.copy(pairingState = PressivePairingState.DialedPersonIsCalling))
    }
    else -> states
        .put(callee.key, callee.copy(isBeingCalled = true))
        .put(caller.key, caller.copy(pairingState = PressivePairingState.Calling(callee.key)))
}

private fun Map<ApiKeyString, SecondPressiveGameParticipantState>.tryToFinishRound(
    orderOfParticipants: Comparator<ApiKey>,
): Map<ApiKeyString, SecondPressiveGameParticipantState> =
    if (values.all { it.pairingState is SuccessFullyPaired })
        mapValues { (me, myState) ->
            myState.copy(pairingState = PressivePairingState.RoundSuccess(
                orderOfParticipants.compare(ApiKey(me), (myState.pairingState as SuccessFullyPaired).partner) < 1
            ))
        }
    else this

private fun SecondPressiveGameParticipantState.successfullyPairedWith(other: ApiKey): SecondPressiveGameParticipantState =
    copy(pairingState = SuccessFullyPaired(other), isBeingCalled = false)

internal fun newRandomPresses(random: Random): List<PressiveGamePressType> = generateSequence {
    generateSequence { PressiveGamePressType.entries.random(random) }
        .take(NumberOfPressesNeededForFirstPressiveGame)
        .toList()
}.first { it.toSet() == PressiveGamePressType.entries.toSet() /* Make sure we force them to press all types! */ }

private fun PressiveGamePressType.asUserReadableString(): String = when (this) {
    PressiveGamePressType.SinglePress -> "short press"
    PressiveGamePressType.DoublePress -> "double press"
    PressiveGamePressType.LongPress -> "long press"
}

private fun PressiveGameState.SecondGameInProgress.hangUp(ditchedPerson: ApiKey): PressiveGameState = copy(
    states = states.put(ditchedPerson, states[ditchedPerson]!!.copy(
        isBeingCalled = false,
        pairingState = PressivePairingState.PartnerHungUp
    ))
)

private operator fun <V> Map<ApiKeyString, V>.get(key: ApiKey): V? = this[key.stringRepresentation]
internal fun <V> Map<ApiKeyString, V>.put(key: ApiKey, value: V): Map<ApiKeyString, V> =
    put(key.stringRepresentation, value)
