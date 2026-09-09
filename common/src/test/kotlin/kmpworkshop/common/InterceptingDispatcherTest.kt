package kmpworkshop.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InterceptingDispatcherTest {
    @Test
    fun `cancelling a yielded immediate coroutine still runs its tracked resumption`() = runTest {
        var active = 0
        var cleanedUp = false
        withContext(UnconfinedTestDispatcher(testScheduler)) {
            withInterceptingDispatcher(
                onDispatchScheduled = { active++ },
                onDispatchedRunnableComplete = { active-- },
            ) {
                val child = launch(start = UNDISPATCHED) {
                    try {
                        yield()
                        awaitCancellation()
                    } finally {
                        cleanedUp = true
                    }
                }
                child.cancelAndJoin()
            }
        }
        assertTrue(cleanedUp)
        assertEquals(0, active)
    }

    @Test
    fun `immediate execution and resumptions stay tracked`() = runTest {
        var active = 0
        withContext(UnconfinedTestDispatcher(testScheduler)) {
            withInterceptingDispatcher(
                onDispatchScheduled = { active++ },
                onDispatchedRunnableComplete = { active-- },
            ) {
                assertTrue(active > 0)
                launch {
                    assertTrue(active > 0)
                    yield()
                    assertTrue(active > 0)
                    delay(1)
                    assertTrue(active > 0)
                }.join()
                assertTrue(active > 0)
            }
        }
        assertEquals(0, active)
    }
}
