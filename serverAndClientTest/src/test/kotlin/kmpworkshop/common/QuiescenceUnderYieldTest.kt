package kmpworkshop.common

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.coroutines.resume
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * A coroutine that merely [yield]s is not idle - it hands the thread over but resumes itself immediately - so it
 * must keep [autoBatchedOnQuiescence]'s active-coroutine count up while it does. The intercepting dispatcher used to
 * forward `dispatchYield` untracked, which made a yielding coroutine momentarily invisible to that count; quiescence
 * could then be declared mid-yield and a batch could fire with only part of a concurrent group.
 *
 * This pins the guarantee down directly: three coroutines that each yield a few times before joining the batch must
 * all land in the *same* batch, across every shuffled interleaving [withRandomizedDispatchOrder] can produce.
 */
@OptIn(ExperimentalTime::class)
class QuiescenceUnderYieldTest {
    @Test
    fun `coroutines that yield before joining a batch stay active, so they still batch together`() {
        for (seed in 0L until 300L) {
            try {
                runTest(timeout = 10.seconds) {
                    withRandomizedDispatchOrder(seed) {
                        val batchSizes = mutableListOf<Int>()
                        val fn = AutoBatchedFunctionId<Int, Int> { batch ->
                            batchSizes.add(batch.size)
                            batch.forEach { it.continuation.resume(it.query) }
                        }
                        val results = fn.autoBatchedOnQuiescence {
                            (0 until 3).map { i ->
                                async {
                                    repeat(3) { yield() }
                                    fn.batched(i)
                                }
                            }.awaitAll()
                        }
                        assertEquals((0 until 3).toList(), results.sorted())
                        assertEquals(
                            listOf(3),
                            batchSizes,
                            "all three concurrent calls must land in a single batch, not be split by a premature quiescence",
                        )
                    }
                }
            } catch (t: Throwable) {
                throw AssertionError("Failed with dispatch-order seed $seed", t)
            }
        }
    }
}
