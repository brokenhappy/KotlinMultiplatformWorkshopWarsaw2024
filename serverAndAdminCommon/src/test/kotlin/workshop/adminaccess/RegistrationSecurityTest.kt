package workshop.adminaccess

import kmpworkshop.common.ApiKey
import kmpworkshop.common.ApiKeyRegistrationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegistrationSecurityTest {
    @Test
    fun `rejects an exact duplicate of a pending name without consuming a slot`() {
        val (pendingState, firstResult) = RegistrationStartEvent("Ada", 1).applyWithResultTo(ServerState())
        assertIs<ApiKeyRegistrationResult.Success>(firstResult)

        val (unchangedState, duplicateResult) = RegistrationStartEvent("Ada", 2).applyWithResultTo(pendingState)

        assertEquals(ApiKeyRegistrationResult.NameAlreadyExists, duplicateResult)
        assertEquals(pendingState, unchangedState)
        for (distinctName in listOf("ada", "Ada ")) {
            assertIs<ApiKeyRegistrationResult.Success>(
                RegistrationStartEvent(distinctName, 3).applyWithResultTo(pendingState).second,
            )
        }
    }

    @Test
    fun `bounds the total registration state`() {
        val fullState = ServerState(
            unverifiedParticipants = List(MaxRegistrations) { index ->
                Participant("Pending $index", ApiKey("key-$index"))
            },
        )

        val (unchangedState, result) = RegistrationStartEvent("Ada", 1).applyWithResultTo(fullState)

        assertEquals(ApiKeyRegistrationResult.CapacityReached, result)
        assertEquals(fullState, unchangedState)
    }
}
