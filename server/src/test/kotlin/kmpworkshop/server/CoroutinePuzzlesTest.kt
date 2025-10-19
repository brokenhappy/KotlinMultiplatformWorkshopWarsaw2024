package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.User
import kmpworkshop.common.UserDatabaseWithLegacyQueryUser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class CoroutinePuzzlesTest {
    @Test
    fun `empty solutions are wrong`(): Unit = runBlocking {
        doSimpleSumPuzzle { }.assertIsNotOk()
        doTimedSumPuzzle { }.assertIsNotOk()
        doSimpleCollectPuzzle { }.assertIsNotOk()
        doCollectLatestPuzzle { }.assertIsNotOk()
        doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { }.assertIsNotOk()
        doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { }.assertIsNotOk()
    }

    @Test
    fun `regular collect must fail collect latest puzzle`(): Unit = runBlocking {
        doCollectLatestPuzzle { api ->
            api.numbers().collect { api.submit(it) }
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "Regular collect must fail collect latest puzzle" }
            .description
            .assert({ "cancel" in it.lowercase() }) { "Message must mention cancellation" }
            .assert({ "submit" in it.lowercase() }) { "Message must mention submit" }
    }

    @Test
    fun `collectLatest correct solution`(): Unit = runBlocking {
        doCollectLatestPuzzle { api ->
            api.numbers().collectLatest { api.submit(it) }
        }.assertIsOk()
    }

    @Test
    fun `simple flow puzzle does not need collect latest`(): Unit = runBlocking {
        doSimpleCollectPuzzle { api ->
            api.numbers().collect { api.submit(it) }
        }.assertIsOk()
    }

    @Test
    fun `simple flow puzzle might pass with collect latest`(): Unit = runBlocking {
        // Not strictly needed behavior, but I keep it in here to increase coverage
        doSimpleCollectPuzzle { api ->
            api.numbers().collectLatest { api.submit(it) }
        }.assertIsOk()
    }

    @Test
    fun `simple sum correct solution`(): Unit = runBlocking {
        doSimpleSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber())
        }.assertIsOk()
    }

    @Test
    fun `sum of too many numbers`(): Unit = runBlocking {
        doSimpleSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber() + api.getNumber())
        }.assertIsNotOk()
        doTimedSumPuzzle { api ->
            val firstSum = async { api.getNumber() }
            val secondSum = async { api.getNumber() }
            api.submit(api.getNumber() + firstSum.await() + secondSum.await())
        }.assertIsNotOk()
    }

    @Test
    fun `submitting incorrect sum is not ok`(): Unit = runBlocking {
        doSimpleSumPuzzle { api ->
            api.submit(api.getNumber())
        }.assertIsNotOk()
        doSimpleSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber() + 1)
        }.assertIsNotOk()
        doSimpleSumPuzzle { api ->
            api.submit(13)
        }.assertIsNotOk()
    }

    @Test
    fun `submitting in parallel is ok`(): Unit = runBlocking {
        doSimpleSumPuzzle { api ->
            val firstSum = async { api.getNumber() }
            api.submit(api.getNumber() + firstSum.await())
        }.assertIsOk()
    }

    @Test
    fun `timed sum correct solution`(): Unit = runBlocking {
        doTimedSumPuzzle { api ->
            val firstSum = async { api.getNumber() }
            api.submit(api.getNumber() + firstSum.await())
        }.assertIsOk()
    }

    @Test
    fun `timed sum too slow solution fails`(): Unit = runBlocking {
        doTimedSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber())
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "Regular collect must fail collect latest puzzle" }
            .description
            .assert({ "slow" in it.lowercase() }) { "Message must mention it being slow" }
            .assert({ "1.8" in it.lowercase() }) { "Message must mention expected time" }
    }

    @Test
    fun `correct simple maximum age finding solution`(): Unit = runBlocking {
        doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .maxOf { database.queryUser(it).age }
            )
        }.assertIsOk()
    }

    @Test
    fun `correct timed maximum age finding solution`(): Unit = runBlocking {
        doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .map { async { database.queryUser(it) } }
                    .awaitAll()
                    .maxOf { it.age }
            )
        }.assertIsOk()
    }

    @Test
    fun `simple maximum age finding solution should also be solvable in parallel`(): Unit = runBlocking {
        doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .map { async { database.queryUser(it) } }
                    .awaitAll()
                    .maxOf { it.age }
            )
        }.assertIsOk()
    }

    @Test
    fun `simple legacy api solution works without exception and cancellation handling`(): Unit = runBlocking {
        doMappingLegacyApiCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .maxOf { database.queryUserWithoutException(it).age }
            )
        }.assertIsOk()
    }

    @Test
    fun `simple legacy api solution without exception and cancellation handling works in parallel too`(): Unit = runBlocking {
        doMappingLegacyApiCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .map { async { database.queryUserWithoutException(it) } }
                    .awaitAll()
                    .maxOf { it.age }
            )
        }.assertIsOk()
    }

    @Test
    fun `solution without exceptions does not work for the legacy mapping with exceptions puzzle`(): Unit = runBlocking {
        withTimeoutOrNull(2.seconds) {
            doMappingLegacyApiWithExceptionCoroutinePuzzle { database ->
                database.submit(
                    database
                        .getAllIds()
                        .map { async { database.queryUserWithoutException(it) } }
                        .awaitAll()
                        .maxOf { it.age }
                )
            }
        }.let { assert(it == null) { "Must time out!" } }
    }

    @Test
    fun `solution with exceptions but without cancellation does work for the legacy mapping with exceptions puzzle`(): Unit = runBlocking {
        doMappingLegacyApiWithExceptionCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .map { async { database.queryUserWithoutCancellation(it) } }
                    .awaitAll()
                    .maxOf { it.age }
            )
        }.assertIsOk()
    }

    @Test
    fun `full solution is correct for last puzzle`(): Unit = runBlocking {
        doMappingLegacyApiWithCancellationCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .map { async { database.queryUser(it) } }
                    .awaitAll()
                    .maxOf { it.age },
            )
        }.assertIsOk()
    }

    @Test
    fun `synchronous solution for timed maximum age finding fails`(): Unit = runBlocking {
        doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .maxOf { database.queryUser(it).age }
            )
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "synchronous solution for timed maximum age finding should fail" }
            .description
            .assert({ "slow" in it.lowercase() }) { "Message must mention it being slow" }
            .assert({ "3" in it.lowercase() }) { "Message must mention expected time" }

    }

}

private suspend fun UserDatabaseWithLegacyQueryUser.queryUser(id: Int): User {
    return suspendCancellableCoroutine { continuation ->
        queryUserWithCallback(
            id,
            onSuccess = { continuation.resume(it) },
            onError = { continuation.resumeWithException(it) },
        ).let { handle ->
            continuation.invokeOnCancellation {
                handle.cancel(onCancellationFinished = {
                    continuation.resumeWithException(CancellationException())
                })
            }
        }
    }.also { println("Got user $it") }
}

private suspend fun UserDatabaseWithLegacyQueryUser.queryUserWithoutCancellation(id: Int): User {
    return suspendCancellableCoroutine { continuation ->
        queryUserWithCallback(
            id,
            onSuccess = { continuation.resume(it) },
            onError = {
                continuation.resumeWithException(it)
            },
        )
    }
}

private suspend fun UserDatabaseWithLegacyQueryUser.queryUserWithoutException(id: Int): User {
    return suspendCancellableCoroutine { continuation ->
        queryUserWithCallback(id, onSuccess = { continuation.resume(it) })
    }
}

private fun CoroutinePuzzleSolutionResult.assertIsOk(): Unit = when (this) {
    is CoroutinePuzzleSolutionResult.Failure -> fail { this.description }
    CoroutinePuzzleSolutionResult.Success -> { /** All OK! */ }
}

private fun CoroutinePuzzleSolutionResult.assertIsNotOk() {
    assertIs<CoroutinePuzzleSolutionResult.Failure> { "Puzzle succeeded unexpectedly" }
}

internal inline fun <reified T> Any?.assertIs(message: (Any?) -> String): T =
    if (this is T) this else kotlin.test.fail(message(this))

internal inline fun Any?.assertIs(other: Any?, message: (Any?) -> String) {
    assertEquals(this, message(this))
}

internal inline fun <T> T.assert(test: (T) -> Boolean, message: (T) -> String): T =
    this.also { if (!test(this)) kotlin.test.fail(message(this) + "\nActual value was: $this") }
