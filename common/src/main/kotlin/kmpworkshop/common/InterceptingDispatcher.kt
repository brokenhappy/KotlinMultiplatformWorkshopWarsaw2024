package kmpworkshop.common

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.InternalForInheritanceCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

suspend fun <T> withInterceptingDispatcher(
    onDispatchScheduled: () -> Unit,
    onDispatchedRunnableComplete: () -> Unit,
    block: suspend CoroutineScope.() -> T
): T {
    fun prepareForScheduling(runnable: Runnable): Runnable {
        onDispatchScheduled()
        return Runnable {
            try {
                runnable.run()
            } finally {
                onDispatchedRunnableComplete()
            }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    fun wrapDispatcher(delegateDispatcher: CoroutineDispatcher, delegateDelay: Delay): CoroutineDispatcher {
        return object : CoroutineDispatcher(), Delay {
            /**
             * This is a bit of a tricky case, since this isn't just a Runnable we can wrap.
             * The Runnable comes later, namely at the moment that our [delegateDispatcher] calls [continuation].
             * We don't
             */
            override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
                if (isUnlimitedDuration_copiedFromKxCoroutines(timeMillis)) {
                    // In this case, this coroutine will never resume itself, so we consider not to be dispatched.
                    // We're not an "alive" coroutine that will resume ourselves.
                    return
                }
                onDispatchScheduled()
                GlobalScope.launch {
                    runCatching {
                        suspendCancellableCoroutine { wrapperContinuation ->
                            @OptIn(InternalForInheritanceCoroutinesApi::class)
                            delegateDelay.scheduleResumeAfterDelay(timeMillis, wrapperContinuation)
                        }
                    }.also { result ->
                        if (currentCoroutineContext().isActive) {
                            continuation.resumeWith(result)
                        }
                        onDispatchedRunnableComplete()
                    }
                }.also { job -> continuation.invokeOnCancellation { job.cancel() } }
            }

            override fun invokeOnTimeout(
                timeMillis: Long,
                block: Runnable,
                context: CoroutineContext,
            ): DisposableHandle =
                delegateDelay.invokeOnTimeout(timeMillis, prepareForScheduling(block), context)

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                delegateDispatcher.dispatch(context, prepareForScheduling(block))
            }

            override fun dispatchYield(context: CoroutineContext, block: Runnable) {
                delegateDispatcher.dispatchYield(context, block)
            }

            override fun isDispatchNeeded(context: CoroutineContext): Boolean =
                delegateDispatcher.isDispatchNeeded(context)

            override fun limitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher =
                wrapDispatcher(delegateDispatcher.limitedParallelism(parallelism, name), delegateDelay)
        }
    }

    @OptIn(ExperimentalStdlibApi::class, InternalCoroutinesApi::class)
    return withContext(
        wrapDispatcher(
            currentCoroutineContext()[CoroutineDispatcher] ?: Dispatchers.Default,
            currentCoroutineContext()[ContinuationInterceptor] as? Delay ?: DefaultDelay,
        ),
        block,
    )
}

@OptIn(InternalCoroutinesApi::class)
private val DefaultDelay: Delay by lazy {
    // Yikes, but it's marked as @PublishedApi, so they can't break this ABI
    Class.forName("kotlinx.coroutines.DefaultExecutorKt")
        .getMethod("getDefaultDelay")
        .invoke(null) as Delay
}

/** Most of the content is copied from kx coroutines */
private fun isUnlimitedDuration_copiedFromKxCoroutines(durationMillis: Long): Boolean {
    val maxDelayNanos = Long.MAX_VALUE / 2

    fun delayToNanos(timeMillis: Long): Long = when {
        timeMillis <= 0 -> 0L
        timeMillis >= Long.MAX_VALUE / 1_000_000 -> Long.MAX_VALUE
        else -> timeMillis * 1_000_000
    }

    return delayToNanos(durationMillis) > maxDelayNanos
}
