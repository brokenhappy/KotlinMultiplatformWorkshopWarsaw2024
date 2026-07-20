package kmpworkshop.server

import kmpworkshop.common.*
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

fun maximumAgeFindingTheSecondCoroutinePuzzle(mustBeConcurrent: Boolean): CoroutinePuzzle = coroutinePuzzle {
    val database = generateUserDatabase()

    getAllUserIds.expectCall { database.keys.toList() }

    if (mustBeConcurrent) {
        expectingMatchedParallelism {
            repeat(database.size) { launch { expectQueryCall(database) } }
        }
    } else {
        coroutineScope {
            repeat(database.size) { launch { expectQueryCall(database) } }
        }
    }

    val submittedValue = submitNumber.expectCall(Unit)
    verify(submittedValue == 47) { "You submitted $submittedValue, but the oldest user is 47." }
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun expectQueryCall(database: Map<Int, SerializableUser>) {
    queryUserById.expectCall { id -> database.getAndVerifyUserExists(id) }
}

context(_: CoroutinePuzzleBuilderScope)
private fun Map<Int, SerializableUser>.getAndVerifyUserExists(id: Int): SerializableUser = this[id].verifyNotNull {
    "User with id $id does not exist! Please use the ids retrieved from ${getAllUserIds.descriptor.description}"
}

suspend fun doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle(onUse: suspend CoroutineScope.(UserDatabase) -> Unit): CoroutinePuzzleSolutionResult =
    doUserDatabasePuzzle(maximumAgeFindingTheSecondCoroutinePuzzle(mustBeConcurrent = false), onUse)

suspend fun doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle(onUse: suspend CoroutineScope.(UserDatabase) -> Unit): CoroutinePuzzleSolutionResult =
    doUserDatabasePuzzle(maximumAgeFindingTheSecondCoroutinePuzzle(mustBeConcurrent = true), onUse)

private suspend fun doUserDatabasePuzzle(
    puzzle: CoroutinePuzzle,
    onUse: suspend CoroutineScope.(UserDatabase) -> Unit,
): CoroutinePuzzleSolutionResult = puzzle.solve {
    withImportantCleanup {
        onUse(getUserDatabase())
    }
}

fun mappingLegacyApiHappyPathCoroutinePuzzle(): CoroutinePuzzle = coroutinePuzzle {
    val database = generateUserDatabase()
    getAllUserIds.expectCall(database.keys.toList())

    coroutineScope {
        repeat(database.size) {
            launch { expectQueryCall(database) }
        }
    }
    val submittedValue = submitNumber.expectCall(Unit)
    verify(submittedValue == database.values.maxOf { it.age }) {
        "You submitted $submittedValue, but the oldest user is ${database.values.maxOf { it.age }}."
    }
}

fun mappingLegacyApiCoroutinePuzzleWithException(): CoroutinePuzzle = coroutinePuzzle {
    expectationsOfLifetimeAndIdQueryAndSuccessfulQueryCallsExceptLastOne {
        queryUserById.expectCall(null) // Throw an exception on the last call
        queryExceptionThrown.expectCall(Unit) // Expect it to be caught in the scaffolding
        callIsDone.expectCall(Unit)
    }
}

fun mappingLegacyApiCoroutinePuzzleWithEscapingCancellation(): CoroutinePuzzle = coroutinePuzzle {
    expectationsOfLifetimeAndIdQueryAndSuccessfulQueryCallsExceptLastOne { cancellationSignal ->
        expectQueryUserByIdCallThatShouldGetCanceled(cancellationSignal)
        // We don't care yet that cancellation happens is fully awaited for in this step.
        launch { legacyCancellationCompletion.expectCall(Unit) }
        callIsDone.expectCall(Unit)
    }
}

fun mappingLegacyApiCoroutinePuzzleStepFour(): CoroutinePuzzle = coroutinePuzzle {
    expectationsOfLifetimeAndIdQueryAndSuccessfulQueryCallsExceptLastOne { cancellationSignal ->
        expectQueryUserByIdCallThatShouldGetCanceled(cancellationSignal)
        /**
         * Unlike the previous step, we want an explicit guarantee that the cancellation is awaited for.
         * That means that [callIsDone] and [legacyCancellationCompletion] MUST NOT run concurrently.
         */
        expectingMatchedParallelism { legacyCancellationCompletion.expectCall(Unit) }
        callIsDone.expectCall(Unit)
    }
}

context(builder: CoroutinePuzzleBuilderScope)
private suspend fun expectQueryUserByIdCallThatShouldGetCanceled(cancellationSignal: CompletableDeferred<Unit>) {
    queryUserById.expectCall {
        cancellationSignal.complete(Unit) // Now we're signaling to the submission that we should get canceled.
        withTimeoutOrNull(5.seconds) {
            throw awaitCancellationOfMatchingSubmitCall()
        } ?: fail("Your function got canceled, but you left the last query running")
    }
}

context(builder: CoroutinePuzzleBuilderScope)
private suspend fun expectationsOfLifetimeAndIdQueryAndSuccessfulQueryCallsExceptLastOne(
    handleTrailingCalls: suspend CoroutineScope.(
        /** Completing this signals that cancellation should start. If you don't want to get canceled, ignore this. */
        cancellationSignal: CompletableDeferred<Unit>
    ) -> Unit
): Unit = coroutineScope {
    // Completing this hook signals to the submission that it should get canceled.
    // This cancellation happens in scaffolding.
    val cancellationSignal = CompletableDeferred<Unit>()
    launch {
        callLifetime.expectCall {
            cancellationSignal.await()
        }
    }
    val database = generateUserDatabase()

    getAllUserIds.expectCall(database.keys.toList())

    // We let all but the last call (Note the ` - 1`) succeed.
    coroutineScope {
        repeat(database.size - 1) {
            launch { expectQueryCall(database) }
        }
    }
    // Then handle the last call and closing logic
    coroutineScope { handleTrailingCalls(cancellationSignal) }
    /**
     * We should make sure that [callLifetime] completes,
     * otherwise we'd hang. Since everything is done at this point,
     * the cancellation shouldn't affect anything.
     */
    cancellationSignal.complete(Unit)
}

private fun generateUserDatabase(): Map<Int, SerializableUser> = generateSequence { (0..10_000).random() }
    .runningFold(emptySet<Int>()) { acc, id -> acc + id }
    .first { it.size == 8 } // Set of unique ids
    .plus(10_001) // Fix last id for debugging
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

suspend fun doMappingLegacyApiHappyPathCoroutinePuzzle(
    onUse: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleSolutionResult = mappingLegacyApiHappyPathCoroutinePuzzle().solve {
    val scope = this
    withImportantCleanup {
        onUse(getUserDatabaseWithLegacyQueryUser(topLevelScope = scope))
    }
}

suspend fun doMappingLegacyApiWithExceptionCoroutinePuzzle(
    onUse: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleSolutionResult = mappingLegacyApiCoroutinePuzzleWithException().solve {
    mapFromLegacyApiWithScaffolding {
        coroutineScope {
            onUse(it)
        }
    }
}

suspend fun doMappingLegacyApiWithCancellationCoroutinePuzzle(
    onUse: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleSolutionResult = mappingLegacyApiCoroutinePuzzleWithEscapingCancellation().solve {
    mapFromLegacyApiWithScaffolding {
        coroutineScope {
            onUse(it)
        }
    }
}

suspend fun doMappingLegacyApiStepFourCoroutinePuzzle(
    onUse: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleSolutionResult = mappingLegacyApiCoroutinePuzzleStepFour().solve {
    mapFromLegacyApiWithScaffolding {
        coroutineScope {
            onUse(it)
        }
    }
}
