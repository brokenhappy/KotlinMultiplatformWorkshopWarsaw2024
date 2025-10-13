package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.SerializableColor
import kmpworkshop.common.DiscoGameInstruction.*
import org.junit.jupiter.api.Test
import workshop.adminaccess.DiscoGameInstructionRequest
import workshop.adminaccess.DiscoGameState
import workshop.adminaccess.SecondDiscoGameParticipantState
import workshop.adminaccess.ServerState
import workshop.adminaccess.afterDiscoGameGuessSubmission
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class DiscoGameTest {
    @Test
    fun `simple game without mistakes`() {
        val p1 = ApiKey("p1")
        val p2 = ApiKey("p2")
        val p3 = ApiKey("p3")
        val p4 = ApiKey("p4")
        ServerState(
            discoGameState = DiscoGameState.Second.InProgress(
                orderedParticipants = listOf(
                    SecondDiscoGameParticipantState(p1, SerializableColor(0, 0, 0)),
                    SecondDiscoGameParticipantState(p2, SerializableColor(0, 0, 0)),
                    SecondDiscoGameParticipantState(p3, SerializableColor(0, 0, 0)),
                    SecondDiscoGameParticipantState(p4, SerializableColor(0, 0, 0)),
                ),
                progress = 0,
                instructionOrder = listOf(
                    DiscoGameInstructionRequest(p1, Right),
                    DiscoGameInstructionRequest(p2, Down),
                    DiscoGameInstructionRequest(p4, Left),
                    DiscoGameInstructionRequest(p3, Up),
                )
            )
        )
            .asserting { it.progress == 0 }
            .press(p2)
            .asserting { it.progress == 1 }
            .press(p4)
            .press(p3)
            .press(p1)
            .discoGameState
            .assertIs<DiscoGameState.Second.Done> { "Expected game to finish" }
    }
}

@OptIn(ExperimentalTime::class)
private fun ServerState.press(key: ApiKey): ServerState =
    with(Random) { afterDiscoGameGuessSubmission(key, Clock.System.now()) }

private fun ServerState.asserting(assertion: (DiscoGameState.Second.InProgress) -> Boolean): ServerState {
    discoGameState
        .assertIs<DiscoGameState.Second.InProgress> { "Game state is not in progress anymore but $it" }
        .also { assert(assertion(it)) }
    return this
}
