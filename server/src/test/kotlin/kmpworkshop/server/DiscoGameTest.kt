package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.Color
import kmpworkshop.common.DiscoGameInstruction.*
import org.junit.jupiter.api.Test

class DiscoGameTest {
    @Test
    fun `simple game without mistakes`() {
        val p1 = ApiKey("p1")
        val p2 = ApiKey("p2")
        val p3 = ApiKey("p3")
        val p4 = ApiKey("p4")
        ServerState(
            discoGameState = DiscoGameState.InProgress(
                orderedParticipants = listOf(
                    DiscoGameParticipantState(p1, Color(0, 0, 0)),
                    DiscoGameParticipantState(p2, Color(0, 0, 0)),
                    DiscoGameParticipantState(p3, Color(0, 0, 0)),
                    DiscoGameParticipantState(p4, Color(0, 0, 0)),
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
            .assertIs<DiscoGameState.Done> { "Expected game to finish" }
    }
}

private fun ServerState.press(key: ApiKey): ServerState = afterDiscoGameKeyPressBy(key)
private fun ServerState.asserting(assertion: (DiscoGameState.InProgress) -> Boolean): ServerState {
    discoGameState
        .assertIs<DiscoGameState.InProgress> { "Game state is not in progress anymore but $it" }
        .also { assert(assertion(it)) }
    return this
}