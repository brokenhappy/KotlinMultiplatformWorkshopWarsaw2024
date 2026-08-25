package workshop.adminaccess

import kmpworkshop.common.ApiKey
import kmpworkshop.common.ApiKeyRegistrationResult
import kotlin.test.Test
import kotlin.test.assertEquals

class RegistrationSecurityTest {
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
