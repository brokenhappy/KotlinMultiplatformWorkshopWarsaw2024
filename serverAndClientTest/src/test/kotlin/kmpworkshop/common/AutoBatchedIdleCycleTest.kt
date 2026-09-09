package kmpworkshop.common

import com.kotlinworkshop.test.runTestWithRandomizedDispatchOrdering
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AutoBatchedIdleCycleTest {
    @Test
    fun `an empty idle cycle is not conflated while the previous batch is being sent`() = runTestWithRandomizedDispatchOrdering {
        val batches = Channel<List<SuspendedBatchCall<Unit, Unit>>>(Channel.UNLIMITED)
        val releaseBatch = Channel<Unit>(Channel.UNLIMITED)
        val function = AutoBatchedFunctionId<Unit, Unit> { batch ->
            batches.send(batch)
            if (batch.isEmpty()) releaseBatch.receive()
        }
        val task = launch { function.autoBatchedOnQuiescence { function.batched(Unit) } }
        val request = batches.receive().single()

        request.continuation.runOnScopeThatTracksQuiescence { }
        assertEquals(emptyList(), batches.receive())

        // The peer can resume tracked work before the previous send has returned. Both idle
        // states contain zero active coroutines and no requests, but they are distinct batches.
        request.continuation.runOnScopeThatTracksQuiescence { }
        releaseBatch.send(Unit)
        assertEquals(emptyList(), batches.receive())

        request.continuation.runOnScopeThatTracksQuiescence { request.continuation.resume(Unit) }
        releaseBatch.send(Unit)
        task.join()
    }
}
