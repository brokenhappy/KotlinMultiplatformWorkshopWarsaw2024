package kmpworkshop.server

import kmpworkshop.server.InterceptingEvent.Complete
import kmpworkshop.server.InterceptingEvent.Dispatch
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.collections.listOf
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

internal suspend fun <T> withInterceptingDispatcher(
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
fun isUnlimitedDuration_copiedFromKxCoroutines(durationMillis: Long): Boolean {
    val maxDelayNanos = Long.MAX_VALUE / 2

    fun delayToNanos(timeMillis: Long): Long = when {
        timeMillis <= 0 -> 0L
        timeMillis >= Long.MAX_VALUE / 1_000_000 -> Long.MAX_VALUE
        else -> timeMillis * 1_000_000
    }

    return delayToNanos(durationMillis) > maxDelayNanos
}

enum class InterceptingEvent {
    Dispatch,
    Complete,
}

class InterceptingDispatcherTest {
    @Test
    suspend fun `empty body only starts and finishes`() {
        assertEquals(
            listOf(Dispatch, Complete),
            captureDispatchEventsToList { },
        )
    }

    @Test
    suspend fun `launching a coroutine guarantees two opens and two closes`() {
        assertEquals(
            listOf(Dispatch, Dispatch, Complete, Complete),
            captureDispatchEventsToList {
                launch { }
            },
        )
    }

    @Test
    suspend fun `nested launches are observed`() {
        assertEquals(
            listOf(Dispatch, Dispatch, Dispatch, Dispatch, Complete, Complete, Complete, Complete),
            captureDispatchEventsToList {
                launch { launch { launch {} } }
            }.sorted(),
        )
    }

    @Test
    suspend fun `delay 0 changes nothing`() {
        assertEquals(
            listOf(Dispatch, Complete),
            captureDispatchEventsToList {
                delay(0.seconds)
            },
        )
    }

    @Test
    fun `a delay briefly makes it seem like there is 2 concurrent tasks`(): Unit = runTest {
        assertEquals(
            2,
            captureDispatchEventsToList {
                delay(1.seconds)
            }
                .runningFold(0) { acc, it -> acc + if (it == Dispatch) 1 else -1 }
                .max(),
        )
    }

    @Test
    fun `an infinite delay is indistinguishable from awaitCancellation`(): Unit = runTest {
        val awaitCancellationDispatches = MutableStateFlow<List<InterceptingEvent>>(emptyList())
        val delay200YearsDispatches = MutableStateFlow<List<InterceptingEvent>>(emptyList())
        val delayMaxLongDispatches = MutableStateFlow<List<InterceptingEvent>>(emptyList())
        val job = launch {
            launch {
                captureDispatchEvents(onEvent = { event -> awaitCancellationDispatches.update { it + event } }) {
                    awaitCancellation()
                }
            }
            launch {
                captureDispatchEvents(onEvent = { event -> delay200YearsDispatches.update { it + event } }) {
                    /** Should be considered infinite according to [kotlinx.coroutines.MAX_DELAY_NS] */
                    delay(365.days * 200)
                }
            }
            launch {
                captureDispatchEvents(onEvent = { event -> delayMaxLongDispatches.update { it + event } }) {
                    @Suppress("ConvertLongToDuration")
                    delay(Long.MAX_VALUE)
                }
            }
        }

        testScheduler.advanceUntilIdle()
        assertEquals(awaitCancellationDispatches.value, delay200YearsDispatches.value)
        assertEquals(awaitCancellationDispatches.value, delayMaxLongDispatches.value)

        job.cancel()
        testScheduler.advanceUntilIdle()
        assertEquals(awaitCancellationDispatches.value, delay200YearsDispatches.value)
        assertEquals(awaitCancellationDispatches.value, delayMaxLongDispatches.value)
    }
}

private suspend fun captureDispatchEventsToList(function: suspend CoroutineScope.() -> Unit): List<InterceptingEvent> {
    val dispatches = Channel<InterceptingEvent>(Channel.UNLIMITED)
    try {
        captureDispatchEvents(onEvent = dispatches::trySend, function)
    } finally {
        dispatches.close()
    }
    return dispatches.toList()
}

private suspend fun captureDispatchEvents(
    onEvent: (InterceptingEvent) -> Unit,
    function: suspend CoroutineScope.() -> Unit,
) {
    withInterceptingDispatcher(
        onDispatchScheduled = {
            onEvent(Dispatch)
        },
        onDispatchedRunnableComplete = {
            onEvent(Complete)
        },
        block = function,
    )
}