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
             * Everything dispatched between two drains is, from this dispatcher's point of view, "ready at the same
             * moment" - so instead of forwarding each dispatch immediately (which would preserve enqueue order), we
             * buffer them and release the whole batch, shuffled, via a single follow-up dispatch. Since dispatching
             * this drain itself goes through [delegateDispatcher] (not straight execution), any dispatch calls made
             * synchronously before it runs are guaranteed to have already landed in [pending].
             */
            private fun scheduleDrain() {
                if (drainScheduled) return
                drainScheduled = true
                delegateDispatcher.dispatch(EmptyCoroutineContext, Runnable {
                    drainScheduled = false
                    val batch = pending.toList()
                    pending.clear()
                    batch.shuffled(random).forEach { (context, runnable) -> delegateDispatcher.dispatch(context, runnable) }
                })
            }

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                pending.add(context to block)
                scheduleDrain()
            }

            override fun dispatchYield(context: CoroutineContext, block: Runnable) {
                delegateDispatcher.dispatchYield(context, block)
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
