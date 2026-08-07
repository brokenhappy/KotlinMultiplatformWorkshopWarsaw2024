package kmpworkshop.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class AssumeNotQuiescentTest {
    @Test
    fun `an externally resumed channel send does not look quiescent`() = runTest {
        val channel = Channel<Unit>(Channel.RENDEZVOUS)
        val batchResumes = CompletableDeferred<Unit>()
        val function = AutoBatchedFunctionId<Unit, Unit> { batch ->
            if (batch.isEmpty()) batchResumes.complete(Unit)
            batch.resumeAllQuiescentTrackedScope { it.continuation.resume(Unit) }
        }

        val run = async {
            function.autoBatchedOnQuiescence {
                launch {
                    assumeNotQuiescent { channel.send(Unit) }
                }.join()
            }
        }

        repeat(3) {
            runCurrent()
            yield()
        }
        assertFalse(batchResumes.isCompleted, "a blocked debugger send must not flush an empty batch")

        assertEquals(Unit, channel.receive())
        run.await()
        channel.close()
    }

    @Test
    fun `nested dispatcher work remains non quiescent`() = runTest {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val batchResumes = CompletableDeferred<Unit>()
        val function = AutoBatchedFunctionId<Unit, Unit> { batch ->
            if (batch.isEmpty() && entered.isCompleted) batchResumes.complete(Unit)
            batch.resumeAllQuiescentTrackedScope { it.continuation.resume(Unit) }
        }

        val run = async {
            function.autoBatchedOnQuiescence {
                launch {
                    assumeNotQuiescent {
                        entered.complete(Unit)
                        withContext(Dispatchers.Default) { release.await() }
                    }
                }.join()
            }
        }

        entered.await()
        repeat(3) { yield() }
        assertFalse(batchResumes.isCompleted, "dispatcher work must keep the tracker non-quiescent")

        release.complete(Unit)
        run.await()
    }
}
