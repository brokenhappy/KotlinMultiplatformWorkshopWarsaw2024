package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.SerializableUser
import kmpworkshop.common.UserDatabase
import kmpworkshop.common.UserDatabaseWithLegacyQueryUser
import kmpworkshop.common.callIsDone
import kmpworkshop.common.callLifetime
import kmpworkshop.common.cancelQuery
import kmpworkshop.common.queryExceptionThrown
import kmpworkshop.common.getAllUserIds
import kmpworkshop.common.getUserDatabase
import kmpworkshop.common.getUserDatabaseWithLegacyQueryUser
import kmpworkshop.common.mapFromLegacyApiWithScaffolding
import kmpworkshop.common.queryUserById
import kmpworkshop.common.solve
import kmpworkshop.common.submitNumber
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

fun maximumAgeFindingTheSecondCoroutinePuzzle(isTimed: Boolean): CoroutinePuzzle = coroutinePuzzle {
    val database = generateUserDatabase()

    getAllUserIds.expectCall { database.keys.toList() }

    suspend fun expectQueryCalls(isTimed: Boolean) {
        expectParallelCalls(database.size) {
            expectQueryCall(isTimed, database)
        }
    }

    if (isTimed) {
        withTimeoutOrNull(3.seconds) {
            expectQueryCalls(isTimed)
        } ?: fail("""
            Your solution is too slow! :(
            We expect you to query all users in less than 3 seconds.
        """.trimIndent())
    } else {
        expectQueryCalls(isTimed = false)
    }


    val submittedValue = submitNumber.expectCall(Unit)
    verify(submittedValue == 47) { "You submitted $submittedValue, but the oldest user is 47." }
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun expectQueryCall(isTimed: Boolean, database: Map<Int, SerializableUser>) {
    queryUserById.expectCall { id ->
        if (isTimed) delay(1.seconds)
        database.getAndVerifyUserExists(id)
    }
}

context(_: CoroutinePuzzleBuilderScope)
private fun Map<Int, SerializableUser>.getAndVerifyUserExists(id: Int): SerializableUser = this[id].verifyNotNull {
    "User with id $id does not exist! Please use the ids retrieved from ${getAllUserIds.description}"
}

suspend fun doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle(onUse: suspend CoroutineScope.(UserDatabase) -> Unit): CoroutinePuzzleSolutionResult =
    doUserDatabasePuzzle(maximumAgeFindingTheSecondCoroutinePuzzle(isTimed = false), onUse)

suspend fun doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle(onUse: suspend CoroutineScope.(UserDatabase) -> Unit): CoroutinePuzzleSolutionResult =
    doUserDatabasePuzzle(maximumAgeFindingTheSecondCoroutinePuzzle(isTimed = true), onUse)

private suspend fun doUserDatabasePuzzle(
    puzzle: CoroutinePuzzle,
    onUse: suspend CoroutineScope.(UserDatabase) -> Unit,
): CoroutinePuzzleSolutionResult = puzzle.solve {
    withImportantCleanup {
        onUse(getUserDatabase())
    }
}

fun mappingLegacyApiCoroutinePuzzleWithException(): CoroutinePuzzle = coroutinePuzzle {
    val isDone = CompletableDeferred<Unit>()
    launchBranch {
        callLifetime.expectCall {
            isDone.await()
        }
    }
    val database = generateUserDatabase()

    getAllUserIds.expectCall { database.keys.toList() }

    expectParallelCalls(database.size - 1) {
        expectQueryCall(isTimed = false, database)
    }
    queryUserById.expectCall(null) // The last one throws an exception
    queryExceptionThrown.expectCall(Unit)
    callIsDone.expectCall(Unit)
    isDone.complete(Unit) // As very, very last, so that we don't accidentally cancel any of their stuff.
}

@OptIn(ExperimentalAtomicApi::class)
fun mappingLegacyApiCoroutinePuzzleWithCancellation(): CoroutinePuzzle = coroutinePuzzle {
    val timeToCancel = CompletableDeferred<Unit>()
    launchBranch {
        callLifetime.expectCall {
            timeToCancel.await()
        }
    }
    val database = generateUserDatabase()

    getAllUserIds.expectCall { database.keys.toList() }

    expectParallelCalls(database.size - 1) {
        expectQueryCall(isTimed = false, database)
    }
    // TODO: Fix this!!
    delay(3.seconds) // Wth, this is necessary because the parallel calls somehow aren't encapsulated in their puzzleScope {}?!?!
    val hasCancelled = CompletableDeferred<Unit>()
    puzzleScope {
        launchBranch {
            cancelQuery.expectCall(Unit)
            hasCancelled.complete(Unit)
        }
        queryUserById.expectCall { id ->
            timeToCancel.complete(Unit) // we're going to cancel the last call
            withTimeoutOrNull(5.seconds) {
                hasCancelled.await()
            } ?: fail("Your function got canceled, but you left the last query running")
            database.getAndVerifyUserExists(id)
        }
    }
    callIsDone.expectCall(Unit)
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun expectParallelCalls(
    parallelism: Int,
    parallelExpectations: suspend context(CoroutinePuzzleBuilderScope) () -> Unit,
) {
    puzzleScope {
        repeat(parallelism - 1) {
            launchBranch {
                parallelExpectations()
            }
        }
        parallelExpectations()
    }
}

private fun generateUserDatabase(): Map<Int, SerializableUser> = generateSequence { (0..10_000).random() }
    .runningFold(emptySet<Int>()) { acc, id -> acc + id }
    .first { it.size == 9 } // Set of unique ids
    .zip(
        listOf(
            SerializableUser("Alice", 10),
            SerializableUser("Bob", 15),
            SerializableUser("Charlie", 20),
            SerializableUser("Alice's dad", 35),
            SerializableUser("Bob's dad", 43),
            SerializableUser("Charlie's dad", 44),
            SerializableUser("Alice's mom", 39),
            SerializableUser("Bob's mom", 47),
            SerializableUser("Charlie's mom", 46),
        )
    )
    .toMap()

suspend fun doMappingLegacyApiCoroutinePuzzle(
    onUse: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleSolutionResult = maximumAgeFindingTheSecondCoroutinePuzzle(isTimed = false).solve {
    withImportantCleanup {
        onUse(getUserDatabaseWithLegacyQueryUser())
    }
}

suspend fun doMappingLegacyApiWithExceptionCoroutinePuzzle(
    onUse: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleSolutionResult = mappingLegacyApiCoroutinePuzzleWithException().solve {
    val database = getUserDatabaseWithLegacyQueryUser()
    mapFromLegacyApiWithScaffolding(database) {
        coroutineScope {
            onUse(it)
        }
    }
}

suspend fun doMappingLegacyApiWithCancellationCoroutinePuzzle(
    onUse: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleSolutionResult = mappingLegacyApiCoroutinePuzzleWithCancellation().solve {
    mapFromLegacyApiWithScaffolding(getUserDatabaseWithLegacyQueryUser()) {
        onUse(it)
    }
}
