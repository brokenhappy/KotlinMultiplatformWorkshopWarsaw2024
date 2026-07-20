package kmpworkshop.common

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.InternalForInheritanceCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/**
 * Runs [block] on a dispatcher that shuffles the relative order of coroutines that become ready to run "at the same
 * moment" (i.e. everything dispatched between two quiescence points), using [seed].
 *
 * Under `runTest`'s virtual time, [kotlinx.coroutines.test.TestCoroutineScheduler] always runs same-instant tasks in
 * the exact order they were enqueued, so a test only ever exercises *one* interleaving of a race. Running the same
 * test body many times with different [seed]s surfaces the other interleavings without leaving virtual time (so it
 * stays deterministic/reproducible per seed and doesn't introduce real flakiness).
 *
 * This only reorders [CoroutineDispatcher.dispatch] (i.e. `launch`/continuation resumption). Delay-based scheduling
 * ([Delay.scheduleResumeAfterDelay]/[Delay.invokeOnTimeout]) is left untouched, since its ordering is already
 * meaningful (it's driven by the virtual clock, not enqueue order).
 */
suspend fun <T> withRandomizedDispatchOrder(
    seed: Long,
    block: suspend CoroutineScope.() -> T,
): T {
    val random = Random(seed)

    @OptIn(InternalCoroutinesApi::class)
    fun wrapDispatcher(delegateDispatcher: CoroutineDispatcher, delegateDelay: Delay): CoroutineDispatcher {
        return object : CoroutineDispatcher(), Delay {
            private val pending = mutableListOf<Pair<CoroutineContext, Runnable>>()
            private var drainScheduled = false

            /**
             * Everything dispatched since the previous release is, from this dispatcher's point of view, "ready at
             * the same moment", so we buffer it in [pending] and release it in a randomized order instead of the
             * enqueue order the delegate would preserve.
             *
             * Crucially we release only ONE randomly-chosen runnable per drain. When that runnable runs it may
             * re-dispatch its coroutine's next step straight back into [pending], where it then competes on equal
             * footing with everything still waiting - so a coroutine can get several steps ahead of another, not
             * merely advance one lockstep round at a time. Releasing the whole batch each round (a previous
             * approach) only ever shuffled the order *within* a round, leaving every "one coroutine runs ahead"
             * interleaving unreachable - and those are exactly the interleavings most races need. Since scheduling
             * the drain itself goes through [delegateDispatcher] (not straight execution), any dispatch calls made
             * synchronously before it runs are guaranteed to have already landed in [pending].
             */
            private fun scheduleDrain() {
                if (drainScheduled) return
                drainScheduled = true
                delegateDispatcher.dispatch(EmptyCoroutineContext, Runnable {
                    drainScheduled = false
                    if (pending.isEmpty()) return@Runnable
                    val (context, runnable) = pending.removeAt(random.nextInt(pending.size))
                    delegateDispatcher.dispatch(context, runnable)
                    if (pending.isNotEmpty()) scheduleDrain()
                })
            }

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                pending.add(context to block)
                scheduleDrain()
            }

            /**
             * Route yields through the same randomized [pending] buffer as [dispatch]. Forwarding them straight to
             * the delegate (as before) meant `yield()` points kept their strict FIFO order and weren't shuffled at
             * all - so multi-step coroutines could only ever advance in lockstep no matter the seed.
             */
            override fun dispatchYield(context: CoroutineContext, block: Runnable) {
                pending.add(context to block)
                scheduleDrain()
            }

            override fun isDispatchNeeded(context: CoroutineContext): Boolean =
                delegateDispatcher.isDispatchNeeded(context)

            override fun limitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher =
                wrapDispatcher(delegateDispatcher.limitedParallelism(parallelism, name), delegateDelay)

            @OptIn(InternalForInheritanceCoroutinesApi::class)
            override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
                delegateDelay.scheduleResumeAfterDelay(timeMillis, continuation)
            }

            @OptIn(InternalForInheritanceCoroutinesApi::class)
            override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
                delegateDelay.invokeOnTimeout(timeMillis, block, context)
        }
    }

    @OptIn(ExperimentalStdlibApi::class, InternalCoroutinesApi::class)
    return withContext(
        wrapDispatcher(
            currentCoroutineContext()[CoroutineDispatcher] ?: Dispatchers.Default,
            currentCoroutineContext()[ContinuationInterceptor] as? Delay ?: DefaultDelayForRandomizedDispatchOrder,
        ),
        block,
    )
}

@OptIn(InternalCoroutinesApi::class)
private val DefaultDelayForRandomizedDispatchOrder: Delay by lazy {
    // Yikes, but it's marked as @PublishedApi, so they can't break this ABI
    Class.forName("kotlinx.coroutines.DefaultExecutorKt")
        .getMethod("getDefaultDelay")
        .invoke(null) as Delay
}
