package kmpworkshop.server

import kmpworkshop.common.AutoBatchedFunctionId
import kmpworkshop.common.autoBatchedOnQuiescence
import kmpworkshop.common.pretendActiveAndRunUnintercepted
import kmpworkshop.common.withInterceptingDispatcher
import kmpworkshop.server.InterceptingEvent.Complete
import kmpworkshop.server.InterceptingEvent.Dispatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

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

@OptIn(ExperimentalTime::class)
class PretendActiveAndRunUninterceptedTest {
    @Test
    fun `is a no-op when nothing is intercepting`(): Unit = runTest {
        assertEquals(42, pretendActiveAndRunUnintercepted { 42 })
    }

    @Test
    fun `propagates the block's result when interception is active`(): Unit = runTest {
        val result = withInterceptingDispatcher(onDispatchScheduled = {}, onDispatchedRunnableComplete = {}) {
            pretendActiveAndRunUnintercepted { "hello" }
        }
        assertEquals("hello", result)
    }

    @Test
    fun `holds back quiescence-triggered batching until the pretended work completes`(): Unit = runTest {
        var batchResumerCalls = 0
        val fn = AutoBatchedFunctionId<Unit, Unit> { batch -> batchResumerCalls++; batch.forEach { it.continuation.resume(Unit) } }
        val untrackedSignal = CompletableDeferred<Unit>()
        launch {
            fn.autoBatchedOnQuiescence {
                launch { pretendActiveAndRunUnintercepted { untrackedSignal.await() } }
                fn.batched(Unit)
            }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(0, batchResumerCalls, "must not batch while pretended-active work is still outstanding")
        untrackedSignal.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, batchResumerCalls)
    }

    @Test
    fun `without pretending active, an untracked await lets quiescence fire prematurely`(): Unit = runTest {
        // Control for the previous test: proves the guarantee actually comes from pretendActiveAndRunUnintercepted,
        // not from some other incidental property of the setup.
        var batchResumerCalls = 0
        val fn = AutoBatchedFunctionId<Unit, Unit> { batch -> batchResumerCalls++; batch.forEach { it.continuation.resume(Unit) } }
        val untrackedSignal = CompletableDeferred<Unit>()
        launch {
            fn.autoBatchedOnQuiescence {
                launch { untrackedSignal.await() }
                fn.batched(Unit)
            }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(1, batchResumerCalls, "without pretending active, quiescence fires as soon as everything is suspended")
        untrackedSignal.complete(Unit)
    }

    @Test
    fun `marks every enclosing interception layer as active, not just the innermost`(): Unit = runTest {
        val outerFn = AutoBatchedFunctionId<Unit, Unit> { batch -> batch.forEach { it.continuation.resume(Unit) } }
        val innerFn = AutoBatchedFunctionId<Unit, Unit> { batch -> batch.forEach { it.continuation.resume(Unit) } }
        var outerCalls = 0
        var innerCalls = 0
        val untrackedSignal = CompletableDeferred<Unit>()
        launch {
            outerFn.autoBatchedOnQuiescence {
                launch {
                    innerFn.autoBatchedOnQuiescence {
                        launch { pretendActiveAndRunUnintercepted { untrackedSignal.await() } }
                        innerFn.batched(Unit)
                        innerCalls++
                    }
                }
                outerFn.batched(Unit)
                outerCalls++
            }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(0, innerCalls, "inner tracker must see the pretended-active work too")
        assertEquals(0, outerCalls, "outer tracker must see the pretended-active work too")
        untrackedSignal.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, innerCalls)
        assertEquals(1, outerCalls)
    }

    @Test
    fun `decrements even when the block throws, after actually holding a window open`(): Unit = runTest {
        // Unlike a throw with no window at all, this proves the decrement is what unblocks quiescence -
        // not just that batching eventually happens regardless of pretendActiveAndRunUnintercepted.
        var batchResumerCalls = 0
        val fn = AutoBatchedFunctionId<Unit, Unit> { batch -> batchResumerCalls++; batch.forEach { it.continuation.resume(Unit) } }
        val untrackedSignal = CompletableDeferred<Unit>()
        launch {
            fn.autoBatchedOnQuiescence {
                launch {
                    runCatching {
                        pretendActiveAndRunUnintercepted {
                            untrackedSignal.await()
                            throw RuntimeException("boom")
                        }
                    }
                }
                fn.batched(Unit)
            }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(0, batchResumerCalls, "must not batch while the about-to-throw work is still outstanding")
        untrackedSignal.complete(Unit)
        // If the decrement leaked after the throw, activeCoroutineCount would stay above 0 forever and this would hang.
        testScheduler.advanceUntilIdle()
        assertEquals(1, batchResumerCalls, "must resume once the throwing work finishes, despite the exception")
    }

    @Test
    fun `each concurrent call holds back quiescence independently, not via a shared flag`(): Unit = runTest {
        // A boolean "is anything pretending?" flag would pass every other test here (they only ever have one
        // pretendActiveAndRunUnintercepted call outstanding at a time), but would let the second call's early
        // finish clear the flag while the first is still outstanding. Only two overlapping calls expose that.
        var batchResumerCalls = 0
        val fn = AutoBatchedFunctionId<Unit, Unit> { batch -> batchResumerCalls++; batch.forEach { it.continuation.resume(Unit) } }
        val signalA = CompletableDeferred<Unit>()
        val signalB = CompletableDeferred<Unit>()
        launch {
            fn.autoBatchedOnQuiescence {
                launch { pretendActiveAndRunUnintercepted { signalA.await() } }
//                launch { pretendActiveAndRunUnintercepted { signalB.await() } }
                fn.batched(Unit)
            }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(0, batchResumerCalls)

        signalA.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(0, batchResumerCalls, "the other call is still outstanding, so quiescence must still be held back")

        signalB.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, batchResumerCalls)
    }

    @Test
    suspend fun `launches inside the block run unobserved, however many there are`() {
        // If the block ran on the still-intercepted dispatcher, each inner launch/join pair would add its own
        // Dispatch/Complete events. Real un-interception makes the event count independent of what runs inside.
        suspend fun dispatchEventCountFor(innerLaunchCount: Int): Int =
            captureDispatchEventsToList {
                pretendActiveAndRunUnintercepted {
                    repeat(innerLaunchCount) { launch { } }
                }
            }.size

        assertEquals(
            dispatchEventCountFor(innerLaunchCount = 0),
            dispatchEventCountFor(innerLaunchCount = 3),
            "extra work inside the block must not add extra dispatch events",
        )
    }

    @Test
    suspend fun `work done inside the block still runs to completion`() {
        var ranInsideBlock = false
        pretendActiveAndRunUnintercepted {
            launch { ranInsideBlock = true }
        }
        assertTrue(ranInsideBlock)
    }
}

private fun runTest(
    context: CoroutineContext = EmptyCoroutineContext,
    timeout: Duration = 1.seconds,
    testBody: suspend TestScope.() -> Unit
) = kotlinx.coroutines.test.runTest(context, timeout) { coroutineScope { testBody() } }

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
