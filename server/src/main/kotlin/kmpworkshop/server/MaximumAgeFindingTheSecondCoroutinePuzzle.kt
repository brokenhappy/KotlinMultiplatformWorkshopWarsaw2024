package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.SerializableUser
import kmpworkshop.common.UserDatabase
import kmpworkshop.common.getAllUserIds
import kmpworkshop.common.getUserDatabase
import kmpworkshop.common.queryUserById
import kmpworkshop.common.solve
import kmpworkshop.common.submitNumber
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

fun maximumAgeFindingTheSecondCoroutinePuzzle(isTimed: Boolean): CoroutinePuzzle = coroutinePuzzle {
    val database = generateSequence { (0..10_000).random() }
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

    getAllUserIds.expectCall { database.keys }

    suspend fun expectQueryCalls(isTimed: Boolean) {
        puzzleScope {
            repeat(database.size - 1) {
                launchBranch {
                    expectQueryCall(isTimed, database)
                }
            }
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

context(builder: CoroutinePuzzleBuilderScope)
private suspend fun expectQueryCall(isTimed: Boolean, database: Map<Int, SerializableUser>) {
    queryUserById.expectCall { id ->
        if (isTimed) delay(1.seconds)
        database[id].verifyNotNull {
            "User with id $id does not exist!" +
                "Please use the ids retrieved from ${getAllUserIds.description}"
        }
    }
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
