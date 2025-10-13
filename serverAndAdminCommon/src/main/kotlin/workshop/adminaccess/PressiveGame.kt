@file:OptIn(ExperimentalTime::class)

package workshop.adminaccess

import kmpworkshop.common.ApiKey
import kmpworkshop.common.PressiveGamePressType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import workshop.adminaccess.PressivePairingState.SuccessFullyPaired
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant


@Serializable
sealed class PressiveGameEvent : WorkshopEvent() {
    @Serializable
    data class StartFirst(val now: Instant, val randomSeed: Long) : PressiveGameEvent()
    @Serializable
    data class StartSecond(val randomSeed: Long) : PressiveGameEvent()
    @Serializable
    data class StartThird(val randomSeed: Long) : PressiveGameEvent()
    @Serializable
    data object DisableAllWhoDidntFinishFirstGame : PressiveGameEvent()
    @Serializable
    data object Tick: PressiveGameEvent()
    @Serializable
    data class Press(
        val now: Instant,
        val randomSeed: Long,
        val participant: ApiKey,
        val pressEvent: PressiveGamePressType,
    ) : WorkshopEventWithResult<String?>() {
        @Transient
        override val serializer: KSerializer<String?> = kotlinx.serialization.serializer()

        override fun applyWithResultTo(oldState: ServerState): Pair<ServerState, String?> =
            with(Random(randomSeed)) { oldState.pressiveGameState.pressing(pressEvent, presserKey = participant, now) }
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
}

sealed class PressiveGamePressResult {
    data class Hint(val message: String): PressiveGamePressResult()
    data class Update(val newState: PressiveGameState): PressiveGamePressResult()
    data class UpdateWithEvent(val update: Update, val event: SoundPlayEvents): PressiveGamePressResult()
}


context(_: Random)
fun PressiveGameState.pressing(
    type: PressiveGamePressType,
    presserKey: ApiKey,
    now: Instant,
): PressiveGamePressResult = when (this) {
    PressiveGameState.NotStarted -> PressiveGamePressResult.Hint("Hold up there fella! We haven't started the game yet :)")
    PressiveGameState.ThirdGameDone,
    PressiveGameState.SecondGameDone,
    is PressiveGameState.FirstGameDone -> PressiveGamePressResult.Hint(
        "I know you're excited, but you're gonna have to wait until we start the next round!\nPerhaps you could help your peers so we are ready faster?"
    )
    is PressiveGameState.FirstGameInProgress -> states[presserKey]?.let { state ->
        when {
            state.finishTime != null -> this.toPressResult()
            type == state.pressesLeft.first() ->
                state.dropSinglePressLeft(now).let { newParticipantState ->
                    copy(states = states.put(presserKey, state.dropSinglePressLeft(now)))
                        .let { newState ->
                            // Mind the early return! We only finish the game if...
                            PressiveGameState.FirstGameDone(
                                startTime = newState.startTime,
                                finishTimes = newState.states.mapValues { (_, participantState) ->
                                    participantState.finishTime ?: return@let newState // ...All the participants finished
                                },
                            )
                        }.let {
                            if (newParticipantState.finishTime == null) it.toPressResult()
                            else it.withEvent(SoundPlayEvents.Success)
                        }
                }
            else -> copy(states = states.put(presserKey, newFirstPressiveGameState(justFailed = true)))
                .toPressResult()
        }
    } ?: PressiveGamePressResult.Hint(
        "You somehow are not part of this Pressive round! Contact the workshop host for help!"
    )
    is PressiveGameState.SecondGameInProgress -> states[presserKey]?.let { presserState ->
        val stateWithProgressIncreasedAndSound =
            if (order[progress] == presserKey) copy(progress = progress + 1).let {
                if (it.progress == order.size)
                    PressiveGameState.SecondGameDone.withEvent(SoundPlayEvents.Success)
                else it.withEvent(SoundPlayEvents.Increment(progress.toDouble() / order.size))
            } else if (progress > 0) copy(progress = 0).withEvent(SoundPlayEvents.ProgressLoss)
            else copy(progress = 0).toPressResult()
        stateWithProgressIncreasedAndSound.map { stateWithProgressIncreased ->
            if (stateWithProgressIncreased !is PressiveGameState.SecondGameInProgress) return@map PressiveGameState.SecondGameDone
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
                    PressiveGameState.ThirdGameDone.withEvent(SoundPlayEvents.Success)
                else if (nextProgress > 0)
                    it.withEvent(SoundPlayEvents.Increment(nextProgress.toDouble() / order.size))
                else if (progress > 0)
                    it.withEvent(SoundPlayEvents.ProgressLoss)
                else it.toPressResult()
            }
        }
}

fun ServerState.after(event: PressiveGameEvent): ServerState = when (event) {
    is PressiveGameEvent.StartFirst -> with(Random(event.randomSeed)) { startingFirstPressiveGame(event.now) }
    is PressiveGameEvent.StartSecond -> with(Random(event.randomSeed)) { startingSecondPressiveGame() }
    is PressiveGameEvent.StartThird -> with(Random(event.randomSeed)) { startingThirdPressiveGame() }
    is PressiveGameEvent.DisableAllWhoDidntFinishFirstGame -> when (val state = pressiveGameState) {
        is PressiveGameState.FirstGameDone -> state.finishTimes.keys.map { ApiKey(it) }
        is PressiveGameState.FirstGameInProgress -> state.states.entries
            .mapNotNull { (key, value) -> ApiKey(key).takeUnless { value.finishTime == null } }
        is PressiveGameState.SecondGameDone,
        is PressiveGameState.SecondGameInProgress,
        is PressiveGameState.NotStarted,
        is PressiveGameState.ThirdGameDone,
        is PressiveGameState.ThirdGameInProgress -> null
    }
        ?.toSet()
        ?.let { keys -> participants.filter { it.apiKey !in keys } }
        ?.let { participants -> participants.fold(this) { acc, key -> acc.after(ParticipantDeactivationEvent(key)) } }
        ?: this
    is PressiveGameEvent.Tick -> when (val state = pressiveGameState) {
        is PressiveGameState.FirstGameDone,
        is PressiveGameState.FirstGameInProgress,
        is PressiveGameState.NotStarted,
        is PressiveGameState.SecondGameDone,
        is PressiveGameState.SecondGameInProgress,
        is PressiveGameState.ThirdGameDone -> this
        is PressiveGameState.ThirdGameInProgress -> copy(
            pressiveGameState = state.copy(
                participantThatIsBeingRung = when (val current = state.participantThatIsBeingRung) {
                    null -> state.order.firstOrNull()
                    else -> state.order.getOrNull(state.order.indexOfFirst { it == current } + 1)
                }
            )
        ).schedulingSingle(PressiveGameEvent.Tick).after(delayForNextEvent(state))
    }
}

private fun delayForNextEvent(lastState: PressiveGameState.ThirdGameInProgress): Duration = when {
    lastState.participantThatIsBeingRung == lastState.order.last() -> 2.seconds // Wait a bit in between cycles
    else -> 300.milliseconds
}

context(_: Random)
private fun ServerState.startingFirstPressiveGame(now: Instant): ServerState = copy(
    pressiveGameState = PressiveGameState.FirstGameInProgress(
        startTime = now,
        states = participants.associate { participant ->
            Pair(
                participant.apiKey.stringRepresentation,
                newFirstPressiveGameState(justFailed = false),
            )
        },
    ),
)

context(random: Random)
private fun ServerState.startingSecondPressiveGame(): ServerState =
    copy(pressiveGameState = PressiveGameState.SecondGameInProgress(
        order = participants.shuffled(random).map { it.apiKey },
        progress = 0,
        states = participants
            .zip(binaryMoreCodeIdentifiers(count = participants.size))
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

context(random: Random)
private fun ServerState.startingThirdPressiveGame(): ServerState =
    copy(
        pressiveGameState = PressiveGameState.ThirdGameInProgress(
            order = participants.shuffled(random).map { it.apiKey },
            progress = 0,
            participantThatIsBeingRung = null,
        )
    ).schedulingSingle(PressiveGameEvent.Tick).after(0.seconds)

fun SecondPressiveGameParticipantState.toHint(state: ServerState): String = """
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

fun FirstPressiveGameParticipantState.toHint(): String = when {
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

fun PressiveGamePressResult.map(mapper: (PressiveGameState) -> PressiveGameState): PressiveGamePressResult = when (this) {
    is PressiveGamePressResult.Hint -> this
    is PressiveGamePressResult.Update -> copy(newState = mapper(newState))
    is PressiveGamePressResult.UpdateWithEvent -> copy(update = update.map(mapper) as PressiveGamePressResult.Update)
}

fun PressiveGameState.toPressResult(): PressiveGamePressResult.Update = PressiveGamePressResult.Update(this)

fun PressiveGameState.withEvent(event: SoundPlayEvents): PressiveGamePressResult.UpdateWithEvent =
    PressiveGamePressResult.UpdateWithEvent(toPressResult(), event)

context(_: Random)
internal fun newFirstPressiveGameState(justFailed: Boolean): FirstPressiveGameParticipantState =
    FirstPressiveGameParticipantState(newRandomPresses(), justFailed = justFailed, finishTime = null)

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

context(random: Random)
internal fun newRandomPresses(): List<PressiveGamePressType> = generateSequence {
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
fun <V> Map<ApiKeyString, V>.put(key: ApiKey, value: V): Map<ApiKeyString, V> =
    put(key.stringRepresentation, value)

// @TestOnly public!!!
context(random: Random)
fun binaryMoreCodeIdentifiers(count: Int): List<String> = count
    .nextPowerOfTwo()
    .let { totalBits ->
        val width = when (count) {
            1 -> 1
            else -> Int.SIZE_BITS - totalBits.countLeadingZeroBits() - 1
        }
        (0..<totalBits)
            .shuffled(random)
            .take(count)
            .map { it.binaryAsMorseCode().padEnd(width, '.') }
    }

// Thanks, AI assistant!
private fun Int.nextPowerOfTwo(): Int {
    if (this <= 0) return 1
    var value = this - 1
    value = value or (value shr 1)
    value = value or (value shr 2)
    value = value or (value shr 4)
    value = value or (value shr 8)
    value = value or (value shr 16)
    return value + 1
}

// 3 => 11 => --
// 4 => 100 => -..
fun Int.binaryAsMorseCode(): String =
    if (this == 0) "" else (if (this % 2 == 0) "." else "-") + (this / 2).binaryAsMorseCode()
