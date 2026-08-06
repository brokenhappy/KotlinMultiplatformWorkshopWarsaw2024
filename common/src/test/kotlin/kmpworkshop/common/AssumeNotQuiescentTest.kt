package kmpworkshop.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
}
