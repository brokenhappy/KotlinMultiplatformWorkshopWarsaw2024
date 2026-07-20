package kmpworkshop.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterizes what [withRandomizedDispatchOrder] actually does, so we know whether it's pulling its weight as a
 * bug finder. It's the tool the in-process puzzle suites lean on to explore races under `runTest`'s virtual time,
 * yet it had never turned one up - so these tests pin down concretely which interleavings it explores (and which it
 * fundamentally can't, being single-threaded).
 *
 * Uses real [yield] rather than the toggleable [yeeld] on purpose: these tests are *about* how the dispatcher
 * shuffles suspension points, so they must not be silenceable along with the instrumentation elsewhere.
 */
class RandomizedDispatchOrderTest {
    private fun traceUnder(seed: Long, body: suspend CoroutineScope.(MutableList<String>) -> Unit): List<String> {
        lateinit var trace: List<String>
        runTest {
            val recorded = mutableListOf<String>()
            withRandomizedDispatchOrder(seed) { body(recorded) }
            trace = recorded.toList()
        }
        return trace
    }

    private fun distinctTracesOverSeeds(
        seeds: LongRange = 0L until 300L,
        body: suspend CoroutineScope.(MutableList<String>) -> Unit,
    ): Set<List<String>> = seeds.map { seed -> traceUnder(seed, body) }.toSet()

    @Test
    fun `shuffles the completion order of concurrently launched coroutines`() {
        val orders = distinctTracesOverSeeds { trace ->
            coroutineScope {
                repeat(5) { i -> launch { trace.add("L$i") } }
            }
        }
        // The plain test dispatcher always yields exactly the enqueue order; the randomizer must produce many.
        assertTrue(orders.size > 1, "Expected many launch orderings, only saw: $orders")
    }

    @Test
    fun `shuffles the order in which coroutines resume from a shared gate`() {
        val orders = distinctTracesOverSeeds { trace ->
            val gate = CompletableDeferred<Unit>()
            coroutineScope {
                repeat(5) { i -> launch { gate.await(); trace.add("R$i") } }
                gate.complete(Unit) // releases all five "at the same moment"
            }
        }
        assertTrue(orders.size > 1, "Expected many resumption orderings, only saw: $orders")
    }

    @Test
    fun `interleaves multi-step coroutines across their yield points`() {
        val traces = distinctTracesOverSeeds { trace ->
            coroutineScope {
                launch { trace.add("A1"); yield(); trace.add("A2"); yield(); trace.add("A3") }
                launch { trace.add("B1"); yield(); trace.add("B2"); yield(); trace.add("B3") }
            }
        }
        // Does either coroutine ever get a whole step ahead of the other? "A2" before "B1" (or symmetrically) means
        // A advanced twice before B advanced once - the kind of interleaving real races usually need. If the only
        // interleavings are lockstep (both advance one step per round, only the within-round order shuffled), that
        // whole class is unreachable and the dispatcher will keep "finding no bugs".
        fun getsAhead(t: List<String>): Boolean =
            t.indexOf("A2") < t.indexOf("B1") || t.indexOf("B2") < t.indexOf("A1")
        val gettingAhead = traces.count { getsAhead(it) }
        assertTrue(traces.size > 1, "Expected many step interleavings, only saw: $traces")
        assertTrue(
            gettingAhead > 0,
            "Randomizer never let one coroutine get a step ahead of another (only lockstep interleavings): $traces",
        )
    }

    @Test
    fun `is reproducible - the same seed always produces the same interleaving`() {
        val body: suspend CoroutineScope.(MutableList<String>) -> Unit = { trace ->
            coroutineScope { repeat(5) { i -> launch { yield(); trace.add("$i") } } }
        }
        for (seed in 0L until 50L) {
            assertEquals(
                traceUnder(seed, body),
                traceUnder(seed, body),
                "seed $seed must reproduce the exact same interleaving",
            )
        }
    }

    @Test
    fun `being single-threaded, it treats each runnable as atomic - so it cannot expose a data race inside one step`() {
        // A read-modify-write with NO suspension point in the middle runs as one indivisible runnable, so no
        // interleaving the randomizer picks can ever interpose another coroutine between the read and the write.
        // Every seed therefore counts perfectly - which is exactly why data races like the autoBatchedOnQuiescence
        // double-resume only show up under a real multi-threaded dispatcher, and need a real-thread stress test.
        for (seed in 0L until 300L) {
            var counter = 0
            traceUnder(seed) {
                coroutineScope {
                    repeat(50) { launch { counter++ } }
                }
            }
            assertEquals(50, counter, "seed $seed: a non-suspending increment can never be lost single-threaded")
        }
    }
}
