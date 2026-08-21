package kmpworkshop.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AutoBatchedFunctionTest {
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
        assertEquals(listOf(listOf(1), listOf(2)), batches.filter { it.isNotEmpty() })
    }
}
