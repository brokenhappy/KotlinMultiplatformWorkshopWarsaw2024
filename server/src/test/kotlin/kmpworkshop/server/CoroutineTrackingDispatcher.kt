package kmpworkshop.server

import kmpworkshop.common.AutoBatchedFunctionId
import kmpworkshop.common.autoBatchedOnQuiescence
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class CoroutineTrackingDispatcherTest {
    @Test
    suspend fun test() {
        val fid = AutoBatchedFunctionId<Int, String> { batchCalls ->
            assertEquals(
                listOf(1, 2, 3),
                batchCalls.map { it.query },
            )
            batchCalls.forEach { request ->
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

data class User(val id: Int, val neighborIds: List<Int>, val powerLevel: Int)

object UserRepositoryImpl {
    suspend fun get(id: Int): User = userBatchFunction.batched(id)
    suspend fun getMultiple(ids: List<Int>): List<User> = TODO("Optimized single network call to get multiple users")
}

val userBatchFunction = AutoBatchedFunctionId<Int, User> { batch ->
    try {
        UserRepositoryImpl
            .getMultiple(batch.map { it.query })
            .zip(batch.map { it.continuation })
            .forEach { (result, continuation) -> continuation.resume(result) }
    } catch (e: Exception) {
        batch.forEach { it.continuation.resumeWithException(e) }
    }
}

@OptIn(ExperimentalTime::class)
suspend fun mostPowerfulNeighborOf(userId: Int): Unit =
    userBatchFunction.autoBatchedOnQuiescence {
        UserRepositoryImpl
            .get(userId) // Will run batch of size 1
            .neighborIds
            .map { neighborId -> async { UserRepositoryImpl.get(neighborId) } }
            .awaitAll() // Will run batch will all neighbor IDs
            .maxBy { it.powerLevel }
    }
