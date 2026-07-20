package kmpworkshop.server

import kmpworkshop.common.AutoBatchedFunctionId
import kmpworkshop.common.autoBatchedOnQuiescence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.coroutines.resume
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * [autoBatchedOnQuiescence]'s tracking loop drives batch resumption through `collectLatest`, which restarts its
 * collector whenever a new state value arrives. A restart can happen *before* the previous (cancellation-shielded)
 * resume has removed the requests it handled from the tracker's state - so the restart would read the same pending
 * requests and resume their continuations a second time, blowing up with `IllegalStateException: Already resumed`.
 *
 * Under `runTest`'s virtual time the scheduler serializes every same-instant task into a single interleaving, so
 * that restart never overlaps a resume and the bug stays invisible. It only surfaces on a real, multi-threaded
 * dispatcher where dispatch/complete events (and therefore state emissions) genuinely race the resume - which is
 * exactly what real RPC traffic produces. Hence this deliberately runs off virtual time on [Dispatchers.Default].
 */
@OptIn(ExperimentalTime::class)
class AutoBatchedOnQuiescenceStressTest {
    @Test
    fun `every concurrent batched call is resumed exactly once under a real multithreaded dispatcher`() =
        runBlocking(Dispatchers.Default) {
            repeat(500) {
                val echo = AutoBatchedFunctionId<Int, Int> { batch ->
                    // Resume asynchronously, like the coroutine-puzzle batchResumer does: it widens the window
                    // between resuming a continuation and the batch being cleared from the tracker's state.
                    batch.forEach { call -> launch { call.continuation.resume(call.query) } }
                }
                // Each resumed call immediately issues the next one, so fresh requests keep landing in the tracker's
                // state *while* earlier batches are still being resumed - which is what makes collectLatest restart
                // mid-resume and (before the fix) re-resume an already-resumed continuation.
                val width = 24
                val depth = 24
                val results = withTimeout(30.seconds) {
                    echo.autoBatchedOnQuiescence {
                        (0 until width).map {
                            async {
                                var sum = 0
                                repeat(depth) { d -> sum += echo.batched(d) }
                                sum
                            }
                        }.awaitAll()
                    }
                }
                assertEquals(List(width) { (0 until depth).sum() }, results)
            }
        }
}
