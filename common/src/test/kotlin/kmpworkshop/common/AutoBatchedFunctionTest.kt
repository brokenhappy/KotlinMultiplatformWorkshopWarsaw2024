package kmpworkshop.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AutoBatchedFunctionTest {
    @Test
    fun `joining an undispatched child does not publish a premature idle batch`() = runTest(UnconfinedTestDispatcher()) {
        val batches = Channel<List<SuspendedBatchCall<Int, Int>>>(Channel.UNLIMITED)
        val function = AutoBatchedFunctionId<Int, Int> { batch -> batches.send(batch) }
        val task = launch {
            function.autoBatchedOnQuiescence {
                launch { function.batched(1) }.join()
                function.batched(2)
            }
        }
        repeat(2) { index ->
            val batch = batches.receive()
            assertEquals(listOf(index + 1), batch.map { it.query })
            batch.resumeAllQuiescentTrackedScope { it.continuation.resume(it.query) }
        }
        task.join()
    }

    @Test
    fun `an undispatched continuation can enter the next batch`() = runTest(UnconfinedTestDispatcher()) {
        val batches = mutableListOf<List<Int>>()
        val function = AutoBatchedFunctionId<Int, Int> { batch ->
            batches += batch.map { it.query }
            batch.resumeAllQuiescentTrackedScope {
                it.continuation.resume(it.query)
            }
        }

        val results = function.autoBatchedOnQuiescence {
            listOf(function.batched(1), function.batched(2))
        }

        assertEquals(listOf(1, 2), results)
        assertEquals(listOf(listOf(1), listOf(2)), batches)
    }
}
