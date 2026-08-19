package bugreproducer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import workshop.adminaccess.ServerState
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeTest {
    @Test
    fun `starts the real server event loop with captured state and cancels it`() = runTest {
        embeddedReproducerRuntime(
            initialState = ServerState(),
            apiKey = "historical-api-key",
        ).use { runtime ->
            assertEquals(ServerState().currentStage, runtime.client.currentStage().first())
        }
    }
}
