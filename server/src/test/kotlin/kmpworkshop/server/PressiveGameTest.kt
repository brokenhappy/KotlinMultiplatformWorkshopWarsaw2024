package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.PressiveGamePressType
import kmpworkshop.common.PressiveGamePressType.DoublePress
import kmpworkshop.common.PressiveGamePressType.LongPress
import kmpworkshop.common.PressiveGamePressType.SinglePress
import kmpworkshop.server.PressivePairingState.Calling
import kmpworkshop.server.PressivePairingState.DialedPersonIsBeingCalled
import kmpworkshop.server.PressivePairingState.DialedPersonIsCalling
import kmpworkshop.server.PressivePairingState.DialedThemselves
import kmpworkshop.server.PressivePairingState.InProgress
import kmpworkshop.server.PressivePairingState.PartnerHungUp
import kmpworkshop.server.PressivePairingState.RoundSuccess
import kmpworkshop.server.PressivePairingState.SuccessFullyPaired
import kmpworkshop.server.PressivePairingState.TriedToCallNonExistingCode
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.fail

class PressiveGameTest {
    @Nested
    inner class SecondGame {
        @Test
        fun `2 players solve without pairing`() {
            val p1 = ApiKey("p1")
            val p2 = ApiKey("p2")
            PressiveGameState.SecondGameInProgress(
                order = listOf(p1, p2),
                states = mapOf(
                    p1.startEntryWithId("-"),
                    p2.startEntryWithId("."),
                ),
            ).pressingProgress(p1, SinglePress)
                .lastPress(p2, SinglePress)
        }

        @Test
        fun `4 players solving with dialing`() {
            val p1 = ApiKey("p1")
            val p2 = ApiKey("p2")
            val p3 = ApiKey("p3")
            val p4 = ApiKey("p4")
            PressiveGameState.SecondGameInProgress(
                order = listOf(p1, p2, p3, p4),
                states = mapOf(
                    p1.startEntryWithId(".."),
                    p2.startEntryWithId(".-"),
                    p3.startEntryWithId("-."),
                    p4.startEntryWithId("--"),
                ),
            ).pressingProgress(p1, SinglePress)
                .pressingProgress(p1, LongPress)
                .assertPairingState(p1) { it is Calling && it.partner == p2 }
                .assertPairingState(p2) { it is InProgress && it.progress == "" }
                .assert(p2) { it.isBeingCalled }
                .assert(p1) { !it.isBeingCalled }
                .pressingProgress(p2, SinglePress)
                .pressingProgress(p2, SinglePress)
                .assertPairingState(p2) { it is SuccessFullyPaired && it.partner == p1 }
                .assertPairingState(p1) { it is SuccessFullyPaired && it.partner == p2 }
                .pressingProgress(p3, LongPress)
                .pressingProgress(p3, LongPress)
                .assertPairingState(p3) { it is Calling && it.partner == p4 }
                .assertPairingState(p4) { it is InProgress && it.progress == "" }
                .assert(p4) { it.isBeingCalled }
                .assert(p3) { !it.isBeingCalled }
                .pressingProgress(p4, LongPress)
                .pressingProgress(p4, SinglePress)
                .assert(p1) { !it.isBeingCalled }
                .assert(p2) { !it.isBeingCalled }
                .assert(p3) { !it.isBeingCalled }
                .assert(p4) { !it.isBeingCalled }
                .assertPairingState(p1) { it is RoundSuccess && it.isPlacedBeforePartner }
                .assertPairingState(p2) { it is RoundSuccess && !it.isPlacedBeforePartner }
                .assertPairingState(p3) { it is RoundSuccess && it.isPlacedBeforePartner }
                .assertPairingState(p4) { it is RoundSuccess && !it.isPlacedBeforePartner }
                .pressingProgress(p1, SinglePress)
                .pressingProgress(p2, SinglePress)
                .pressingProgress(p3, SinglePress)
                .lastPress(p4, SinglePress)
        }

        @Test
        fun `hanging up resets other state`() {
            val p1 = ApiKey("p1")
            val p2 = ApiKey("p2")
            val p3 = ApiKey("p3")
            val p4 = ApiKey("p4")
            PressiveGameState.SecondGameInProgress(
                order = listOf(p1, p2, p3, p4),
                states = mapOf(
                    p1.startEntryWithId(".."),
                    p2.startEntryWithId(".-"),
                    p3.startEntryWithId("-."),
                    p4.startEntryWithId("--"),
                ),
            ).pressingProgress(p1, SinglePress)
                .pressingProgress(p1, LongPress)
                .pressingProgress(p1, DoublePress)
                .assertPairingState(p1) { it is InProgress && it.progress == "" }
                .assert(p2) { !it.isBeingCalled }
                .pressingProgress(p1, SinglePress)
                .pressingProgress(p1, LongPress)
                .pressingProgress(p2, SinglePress)
                .pressingProgress(p2, SinglePress)
                .assertPairingState(p1) { it is SuccessFullyPaired }
                .assertPairingState(p2) { it is SuccessFullyPaired }
                .assert(p1) { !it.isBeingCalled }
                .assert(p2) { !it.isBeingCalled }
                .pressingProgress(p2, DoublePress)
                .assertPairingState(p1) { it is PartnerHungUp }
                .assertPairingState(p2) { it is InProgress && it.progress == "" }
                .assert(p1) { !it.isBeingCalled }
                .assert(p2) { !it.isBeingCalled }
        }

        @Test
        fun `dialing busy numbers`() {
            val p1 = ApiKey("p1")
            val p2 = ApiKey("p2")
            val p3 = ApiKey("p3")
            val p4 = ApiKey("p4")
            PressiveGameState.SecondGameInProgress(
                order = listOf(p1, p2, p3, p4),
                states = mapOf(
                    p1.startEntryWithId(".."),
                    p2.startEntryWithId(".-"),
                    p3.startEntryWithId("-."),
                    p4.startEntryWithId("--"),
                ),
            ).pressingProgress(p1, SinglePress)
                .pressingProgress(p1, LongPress)
                .pressingProgress(p3, SinglePress)
                .pressingProgress(p3, SinglePress)
                .assertPairingState(p3) { it is DialedPersonIsCalling }
                .pressingProgress(p3, SinglePress)
                .pressingProgress(p3, LongPress)
                .assertPairingState(p3) { it is DialedPersonIsBeingCalled }
                .pressingProgress(p2, SinglePress)
                .pressingProgress(p2, SinglePress)
                .pressingProgress(p3, SinglePress)
                .pressingProgress(p3, LongPress)
                .assertPairingState(p3) { it is DialedPersonIsCalling } // Poor p3 :(
        }

        @Test
        fun `dialing yourself`() {
            val p1 = ApiKey("p1")
            val p2 = ApiKey("p2")
            PressiveGameState.SecondGameInProgress(
                order = listOf(p1, p2),
                states = mapOf(
                    p1.startEntryWithId("."),
                    p2.startEntryWithId("-"),
                ),
            ).pressingProgress(p1, SinglePress)
                .assertPairingState(p1) { it is DialedThemselves }
                .pressingProgress(p1, LongPress)
                .assertPairingState(p1) { it is Calling } // Assert is recoverable
        }

        @Test
        fun `dialing wrong code`() {
            val p1 = ApiKey("p1")
            val p2 = ApiKey("p2")
            PressiveGameState.SecondGameInProgress(
                order = listOf(p1, p2),
                states = mapOf(
                    p1.startEntryWithId(".."),
                    p2.startEntryWithId("--"),
                ),
            ).pressingProgress(p1, SinglePress)
                .pressingProgress(p1, LongPress)
                .assertPairingState(p1) { it is TriedToCallNonExistingCode }
                .pressingProgress(p1, LongPress)
                .pressingProgress(p1, LongPress)
                .assertPairingState(p1) { it is Calling } // Assert is recoverable
        }
    }

    @Nested
    inner class FirstGame {
        @Test
        fun `single player`() {
            val p1 = ApiKey("p1")
            val state = PressiveGameState.FirstGameInProgress(
                startTime = Instant.fromEpochMilliseconds(0),
                states = mapOf(
                    p1.stringRepresentation to FirstPressiveGameParticipantState(
                        pressesLeft = listOf(
                            SinglePress,
                            DoublePress,
                            LongPress,
                        ),
                        justFailed = false,
                        finishTime = null,
                    )
                ),
            )
            state
                .pressingProgress(p1, SinglePress)
                .pressingProgress(p1, DoublePress)
                .lastPress(p1, LongPress)
        }
    }
}

private fun PressiveGameState.SecondGameInProgress.assert(
    key: ApiKey,
    test: (SecondPressiveGameParticipantState) -> Boolean,
): PressiveGameState.SecondGameInProgress = assert(key, test, getStateOf = { states[it.stringRepresentation] })

private fun PressiveGameState.FirstGameInProgress.assert(
    key: ApiKey,
    test: (FirstPressiveGameParticipantState) -> Boolean,
): PressiveGameState.FirstGameInProgress = assert(key, test, getStateOf = { states[it.stringRepresentation] })

private fun <State, ParticipantState> State.assert(
    key: ApiKey,
    test: (ParticipantState) -> Boolean,
    getStateOf: State.(ApiKey) -> ParticipantState?,
): State = this.also {
    assert(test(getStateOf(key)!!)) {
        """
            Unexpected state for $key
            Was: ${getStateOf(key)!!}
            Whole state: $this
        """.trimIndent()
    }
}

private fun PressiveGameState.SecondGameInProgress.assertPairingState(
    key: ApiKey,
    test: (PressivePairingState) -> Boolean,
): PressiveGameState.SecondGameInProgress = assert(key) { test(it.pairingState) }

private fun PressiveGameState.SecondGameInProgress.pressingProgress(
    presser: ApiKey,
    type: PressiveGamePressType
): PressiveGameState.SecondGameInProgress = pressing(type, presser, onMessage = {})
    .assertIs<PressiveGameState.SecondGameInProgress> { "Game state changed unexpectedly to $it" }

private fun PressiveGameState.FirstGameInProgress.pressingProgress(
    presser: ApiKey,
    type: PressiveGamePressType
): PressiveGameState.FirstGameInProgress = pressing(type, presser, onMessage = {})
    .assertIs<PressiveGameState.FirstGameInProgress> { "Game state changed unexpectedly to $it" }

private fun PressiveGameState.SecondGameInProgress.lastPress(
    presser: ApiKey,
    type: PressiveGamePressType
): PressiveGameState = pressing(type, presser, onMessage = {})
    .assertIs<PressiveGameState.SecondGameDone> { "Game state was supposed to finish, but is $it" }

private fun PressiveGameState.FirstGameInProgress.lastPress(
    presser: ApiKey,
    type: PressiveGamePressType
): PressiveGameState = pressing(type, presser, onMessage = {})
    .assertIs<PressiveGameState.FirstGameDone> { "Game state was supposed to finish, but is $it" }

internal inline fun <reified T> Any?.assertIs(message: (Any?) -> String): T =
    if (this is T) this else fail(message(this))

private fun ApiKey.startEntryWithId(string: String): Pair<String, SecondPressiveGameParticipantState> =
    stringRepresentation to SecondPressiveGameParticipantState(
        pairingState = InProgress(""),
        this,
        string,
        isBeingCalled = false
    )