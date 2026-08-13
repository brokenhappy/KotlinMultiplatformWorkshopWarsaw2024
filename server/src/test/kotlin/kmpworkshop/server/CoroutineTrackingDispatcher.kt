package kmpworkshop.server

import kmpworkshop.common.AutoBatchedFunctionId
import kmpworkshop.common.autoBatchedOnQuiescence
import kmpworkshop.common.resume
import kmpworkshop.common.resumeAllQuiescentTrackedScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoroutineTrackingDispatcherTest {
    @Test
    suspend fun test() {
        val fid = AutoBatchedFunctionId<Int, String> { batchCalls ->
            assertEquals(
                listOf(1, 2, 3),
                batchCalls.map { it.query },
            )
            batchCalls.resumeAllQuiescentTrackedScope { request ->
                request.continuation.resume(request.query.toString())
            }
        }

        assertEquals(
            listOf("1", "2", "3"),
            fid.autoBatchedOnQuiescence {
                (1..3).map { async { fid.batched(it) } }.awaitAll()
            },
        )
    }
}
