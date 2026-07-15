package kmpworkshop.server

import kmpworkshop.common.withInterceptingDispatcher
import kmpworkshop.server.InterceptingEvent.Complete
import kmpworkshop.server.InterceptingEvent.Dispatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

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
