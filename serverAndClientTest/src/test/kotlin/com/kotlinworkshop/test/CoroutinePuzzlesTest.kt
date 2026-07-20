package com.kotlinworkshop.test

import kmpworkshop.client.runCoroutinePuzzleClient
import kmpworkshop.client.toMessage
import kmpworkshop.common.CoroutinePuzzleSolutionResult.Failure.Reason.ExactParallelismMismatch
import kmpworkshop.common.*
import kmpworkshop.server.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail as junitFail
import workshop.adminaccess.PuzzleState
import workshop.adminaccess.ScheduledWorkshopEvent
import workshop.adminaccess.ServerState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

typealias DoPuzzleWith<Api> = suspend (suspend CoroutineScope.(Api) -> Unit) -> CoroutinePuzzleSolutionResult

class CoroutinePuzzleTestWithoutRpcAbstraction : CoroutinePuzzlesTest(
    doSimpleSumPuzzle = ::doSimpleSumPuzzle,
    doTimedSumPuzzle = ::doTimedSumPuzzle,
    doSimpleCollectPuzzle = ::doSimpleCollectPuzzle,
    doCollectLatestPuzzle = ::doCollectLatestPuzzle,
    doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle = ::doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle,
    doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle = ::doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle,
    doMappingLegacyApiWithExceptionCoroutinePuzzle = ::doMappingLegacyApiWithExceptionCoroutinePuzzle,
    doMappingLegacyApiWithCancellationCoroutinePuzzle = ::doMappingLegacyApiWithCancellationCoroutinePuzzle,
    doMappingLegacyApiStepFourCoroutinePuzzle = ::doMappingLegacyApiStepFourCoroutinePuzzle,
    doMappingLegacyApiHappyPathCoroutinePuzzle = ::doMappingLegacyApiHappyPathCoroutinePuzzle,
)

@OptIn(ExperimentalTime::class)
suspend fun runTestClient(
    stage: WorkshopStage,
    puzzleStates: Map<String, PuzzleState> = mapOf(
        stage.name to PuzzleState.Opened(Clock.System.now(), submissions = emptyMap()),
    ),
    sumSolution: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit = { error("Unexpected puzzle tested") },
    collectSolution: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit = { error("Unexpected puzzle tested") },
    maximumAgeFindingTheSecondCoroutineSolution: suspend CoroutineScope.(UserDatabase) -> Unit = { error("Unexpected puzzle tested") },
    mappingLegacyApiCoroutineSolution: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit = { error("Unexpected puzzle tested") },
): CoroutinePuzzleSolutionResult = coroutineScope {
    val serverState = MutableStateFlow(ServerState(puzzleStates = puzzleStates))
    val eventBus = Channel<ScheduledWorkshopEvent>()
    val job = launch {
        mainEventLoopWritingTo(
            serverState,
            eventBus = eventBus,
            onCommittedState = {},
            onSoundEvent = {},
            onEvent = { launch { eventBus.send(it) } },
        )
    }
    try {
        runCoroutinePuzzleClient(
            workshopServer = workshopService(
                serverState,
                onEvent = { launch { eventBus.send(it) } },
            ).asServer(ApiKey("1234-5678")),
            stage = stage,
            bigScope = this,
            sumSolution = sumSolution,
            collectSolution = collectSolution,
            maximumAgeFindingTheSecondCoroutineSolution = maximumAgeFindingTheSecondCoroutineSolution,
            mappingLegacyApiCoroutineSolution = mappingLegacyApiCoroutineSolution,
        )
    } finally {
        job.cancel()
        eventBus.close()
    }
}

class CoroutinePuzzleTestWithSingleProcessRpcAbstraction : CoroutinePuzzlesTest(
    doSimpleSumPuzzle = { runTestClient(stage = WorkshopStage.SumOfTwoIntsSlow, sumSolution = it) },
    doTimedSumPuzzle = { runTestClient(stage = WorkshopStage.SumOfTwoIntsFast, sumSolution = it) },
    doSimpleCollectPuzzle = { runTestClient(stage = WorkshopStage.SimpleFlow, collectSolution = it) },
    doCollectLatestPuzzle = { runTestClient(stage = WorkshopStage.CollectLatest, collectSolution = it) },
    doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle = {
        runTestClient(stage = WorkshopStage.FindMaximumAgeCoroutines, maximumAgeFindingTheSecondCoroutineSolution = it)
    },
    doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle = {
        runTestClient(stage = WorkshopStage.FastFindMaximumAgeCoroutines, maximumAgeFindingTheSecondCoroutineSolution = it)
    },
    doMappingLegacyApiHappyPathCoroutinePuzzle = {
        runTestClient(stage = WorkshopStage.MappingFromLegacyApisStepOne, mappingLegacyApiCoroutineSolution = it)
    },
    doMappingLegacyApiWithExceptionCoroutinePuzzle = {
        runTestClient(stage = WorkshopStage.MappingFromLegacyApisStepTwo, mappingLegacyApiCoroutineSolution = it)
    },
    doMappingLegacyApiWithCancellationCoroutinePuzzle = {
        runTestClient(stage = WorkshopStage.MappingFromLegacyApisStepThree, mappingLegacyApiCoroutineSolution = it)
    },
    doMappingLegacyApiStepFourCoroutinePuzzle = {
        runTestClient(stage = WorkshopStage.MappingFromLegacyApisStepFour, mappingLegacyApiCoroutineSolution = it)
    },
)

abstract class CoroutinePuzzlesTest(
    private val doSimpleSumPuzzle: DoPuzzleWith<GetNumberAndSubmit>,
    private val doTimedSumPuzzle: DoPuzzleWith<GetNumberAndSubmit>,
    private val doSimpleCollectPuzzle: DoPuzzleWith<NumberFlowAndSubmit>,
    private val doCollectLatestPuzzle: DoPuzzleWith<NumberFlowAndSubmit>,
    private val doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle: DoPuzzleWith<UserDatabase>,
    private val doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle: DoPuzzleWith<UserDatabase>,
    private val doMappingLegacyApiWithExceptionCoroutinePuzzle: DoPuzzleWith<UserDatabaseWithLegacyQueryUser>,
    private val doMappingLegacyApiWithCancellationCoroutinePuzzle: DoPuzzleWith<UserDatabaseWithLegacyQueryUser>,
    private val doMappingLegacyApiStepFourCoroutinePuzzle: DoPuzzleWith<UserDatabaseWithLegacyQueryUser>,
    private val doMappingLegacyApiHappyPathCoroutinePuzzle: DoPuzzleWith<UserDatabaseWithLegacyQueryUser>,
) {
    /**
     * How each transport-driven test body is run. Defaults to the virtual-time, randomized-dispatch harness, which
     * is the right choice for the in-process transports (it shuffles the single virtual-time interleaving across many
     * seeds). Transports whose ordering is decided by something virtual time can't touch - e.g. a real socket - should
     * override this to run once in real time instead, where the transport itself supplies the non-determinism.
     */
    protected open fun runPuzzleTest(block: suspend CoroutineScope.() -> Unit): Unit =
        runTestWithRandomizedDispatchOrdering(block = block)

    @Test
    fun `empty solutions are wrong`(): Unit = runTest(timeout = 1.hours) {
        doSimpleSumPuzzle { }.assertIsNotOk()
        doTimedSumPuzzle { }.assertIsNotOk()
        doSimpleCollectPuzzle { }.assertIsNotOk()
        doCollectLatestPuzzle { }.assertIsNotOk()
        doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { }.assertIsNotOk()
        doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { }.assertIsNotOk()
        doMappingLegacyApiWithExceptionCoroutinePuzzle { }.assertIsNotOk()
        doMappingLegacyApiWithCancellationCoroutinePuzzle { }.assertIsNotOk()
        doMappingLegacyApiStepFourCoroutinePuzzle { }.assertIsNotOk()
        doMappingLegacyApiHappyPathCoroutinePuzzle { }.assertIsNotOk()
    }

    @Test
    fun `regular collect must fail collect latest puzzle`(): Unit = runPuzzleTest {
        doCollectLatestPuzzle { api ->
            api.numbers().collect { api.submit(it) }
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "Regular collect must fail collect latest puzzle" }
            .toMessage()
            .assert({ "cancel" in it.lowercase() }) { "Message must mention cancellation" }
            .assert({ "submit" in it.lowercase() }) { "Message must mention submit" }
    }

    @Test
    fun `collectLatest correct solution`(): Unit = runPuzzleTest {
        doCollectLatestPuzzle { api ->
            api.numbers().collectLatest {
                api.submit(it)
            }
        }.assertIsOk()
    }

    @Test
    fun `simple flow puzzle does not need collect latest`(): Unit = runPuzzleTest {
        doSimpleCollectPuzzle { api ->
            api.numbers().collect { api.submit(it) }
        }.assertIsOk()
    }

    @Test
    fun `simple flow puzzle might pass with collect latest`(): Unit = runPuzzleTest {
        // Not strictly needed behavior, but I keep it in here to increase coverage
        doSimpleCollectPuzzle { api ->
            api.numbers().collectLatest { api.submit(it) }
        }.assertIsOk()
    }

    @Test
    fun `simple sum correct solution`(): Unit = runPuzzleTest {
        doSimpleSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber())
        }.assertIsOk()
    }

    @Test
    fun `sum of too many numbers`(): Unit = runPuzzleTest {
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
    fun `submitting incorrect sum is not ok`(): Unit = runPuzzleTest {
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
    fun `submitting in parallel is ok`(): Unit = runPuzzleTest {
        doSimpleSumPuzzle { api ->
            val firstSum = async { api.getNumber() }
            api.submit(api.getNumber() + firstSum.await())
        }.assertIsOk()
    }

    @Test
    fun `timed sum correct solution`(): Unit = runPuzzleTest {
        doTimedSumPuzzle { api ->
            val firstSum = async { api.getNumber() }
            api.submit(api.getNumber() + firstSum.await())
        }.assertIsOk()
    }

    @Test
    fun `timed sum too slow solution fails`(): Unit = runPuzzleTest {
        doTimedSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber())
        }
            .assertIsNotOk()
            .reason
            .assertIs<ExactParallelismMismatch>()
    }

    @Test
    fun `correct simple maximum age finding solution`(): Unit = runPuzzleTest {
        doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .maxOf { database.queryUser(it).age }
            )
        }.assertIsOk()
    }

    @Test
    fun `correct timed maximum age finding solution`(): Unit = runPuzzleTest {
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
    fun `simple maximum age finding solution should also be solvable in parallel`(): Unit = runPuzzleTest {
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
    fun `simple legacy api solution works without exception and cancellation handling`(): Unit = runPuzzleTest {
        doMappingLegacyApiHappyPathCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .maxOf { database.queryUserHappyPath(it).age }
            )
        }.assertIsOk()
    }

    @Test
    fun `simple legacy api solution without exception and cancellation handling works in parallel too`(): Unit = runPuzzleTest {
        doMappingLegacyApiHappyPathCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .map { async { database.queryUserHappyPath(it) } }
                    .awaitAll()
                    .maxOf { it.age }
            )
        }.assertIsOk()
    }

    @Test
    fun `solution without exceptions does not work for the legacy mapping with exceptions puzzle`(): Unit = runPuzzleTest {
        doMappingLegacyApiWithExceptionCoroutinePuzzle { database ->
            assertFails {
                database
                    .getAllIds()
                    .map { async { database.queryUserHappyPath(it) } }
                    .awaitAll()
            }
        }
    }

    @Test
    fun `solution with exceptions but without cancellation does work for the legacy mapping with exceptions puzzle`(): Unit = runPuzzleTest {
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
    fun `correct solution for legacy api with cancellation puzzle`(): Unit = runPuzzleTest {
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
    fun `correct solution for legacy api step four puzzle`(): Unit = runPuzzleTest {
        doMappingLegacyApiStepFourCoroutinePuzzle { database ->
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
    fun `solution that forgets to await cancellation completion on legacy api mapping fails`(): Unit = runPuzzleTest {
        doMappingLegacyApiStepFourCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .map { async { database.queryUserThatDoesntWaitForCancellationToComplete(it) } }
                    .awaitAll()
                    .maxOf { it.age },
            )
        }.assertIsNotOk()
    }

    @Test
    fun `synchronous solution for timed maximum age finding fails`(): Unit = runPuzzleTest {
        doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .maxOf { database.queryUser(it).age }
            )
        }
            .assertIsNotOk()
            .reason
            .assertIs<ExactParallelismMismatch>()
    }
}

class CoroutinePuzzleUtilitiesTest {
    @Test
    fun `internal calls are NOT shown in history of error message`() = runTestWithRandomizedDispatchOrdering {
        val publicEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("public")
        coroutinePuzzle {
            callLifetime.expectCall(Unit)
        }.solve {
            callLifetime.submitCall(Unit)
            publicEndpoint.submitCall(Unit) // Should result in error
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure>()
            .toMessage()
            .assert({ "lifetime" !in it.lowercase() }) { "Message must not mention internal endpoint" }
    }

    @Test
    fun `internal calls ARE shown in expected calls part of error message`() = runTestWithRandomizedDispatchOrdering {
        val publicEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("public")
        coroutinePuzzle {
            publicEndpoint.expectCall(Unit)
        }.solve {
            callLifetime.submitCall(Unit)
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure>()
            .toMessage()
            .assert({ "lifetime" in it.lowercase() }) { "Message must mention internal endpoint" }
    }

    class ExceptionForTestBelow() : Exception("Test exception")

    @Test
    fun `error that happens in expect call is thrown into submit call`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")
        coroutinePuzzle {
            endpoint.expectCall { throw ExceptionForTestBelow() }
        }.solve {
            assertThrows<ExceptionForTestBelow> {
                endpoint.submitCall(Unit)
            }
        }
    }

    @Test
    fun `nothing hangs when submit call gets canceled`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")
        val cancellationStartHook = CompletableDeferred<Unit>()
        val cancellationFinishedHook = CompletableDeferred<Unit>()
        coroutinePuzzle {
            endpoint.expectCall {
                cancellationStartHook.complete(Unit)
                cancellationFinishedHook.await()
            }
        }.solve {
            launch {
                endpoint.submitCall(Unit)
            }.sideEffect {
                cancellationStartHook.await()
                it.cancelAndJoin()
                cancellationFinishedHook.complete(Unit)
            }
        }
    }

    class SpecialCancellationExceptionForTestBelow() : CancellationException()

    @Test
    fun `await cancellation of matching submit call does not throw into coroutine puzzle scope`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")
        val cancellationStartHook = CompletableDeferred<Unit>()
        coroutinePuzzle {
            try {
                endpoint.expectCall {
                    cancellationStartHook.complete(Unit)
                    assertThrows<SpecialCancellationExceptionForTestBelow> {
                        throw awaitCancellationOfMatchingSubmitCall()
                    }
                }
            } catch (t: Throwable) {
                junitFail("Exception should not be thrown, not even cancellation", t)
            }
        }.solve {
            launch {
                endpoint.submitCall(Unit)
            }.sideEffect {
                cancellationStartHook.await()
                it.cancel(SpecialCancellationExceptionForTestBelow())
            }
        }
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint synchronously while the expectation is parallel fails`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Int, String>("foo")
        coroutinePuzzle {
            expectingMatchedParallelism {
                launch {
                    endpoint.expectCall { it.toString() }
                }
                endpoint.expectCall { it.toString() }
            }
        }.solve {
            endpoint.submitCall(42)
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "Synchronous submission should fail an exact-parallelism expectation" }
            .reason.assertIs<ExactParallelismMismatch>()
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint in parallel while the expectation is synchronous fails`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Int, String>("foo")
        coroutinePuzzle {
            expectingMatchedParallelism {
                endpoint.expectCall { it.toString() }
            }
        }.solve {
            launch { endpoint.submitCall(42) }
            endpoint.submitCall(42)
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "Parallel submission should fail a synchronous exact-parallelism expectation" }
            .reason.assertIs<ExactParallelismMismatch>()
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint with double parallel while the expectation is triple parallel fails`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Int, String>("foo")
        coroutinePuzzle {
            expectingMatchedParallelism {
                launch { endpoint.expectCall { it.toString() } }
                launch { endpoint.expectCall { it.toString() } }
                endpoint.expectCall { it.toString() }
            }
        }.solve {
            launch { endpoint.submitCall(42) }
            endpoint.submitCall(42)
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "Double parallel submission should fail a triple-parallel expectation" }
            .reason.assertIs<ExactParallelismMismatch>()
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint with triple parallel while the expectation is double parallel fails`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Int, String>("foo")
        coroutinePuzzle {
            expectingMatchedParallelism {
                launch { endpoint.expectCall { it.toString() } }
                endpoint.expectCall { it.toString() }
            }
        }.solve {
            launch { endpoint.submitCall(42) }
            launch { endpoint.submitCall(42) }
            endpoint.submitCall(42)
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "Triple parallel submission should fail a double-parallel expectation" }
            .reason.assertIs<ExactParallelismMismatch>()
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint with matching parallelism succeeds`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Int, String>("foo")
        coroutinePuzzle {
            expectingMatchedParallelism {
                launch { endpoint.expectCall { it.toString() } }
                launch { endpoint.expectCall { it.toString() } }
                endpoint.expectCall { it.toString() }
            }
        }.solve {
            launch { endpoint.submitCall(42) }
            launch { endpoint.submitCall(42) }
            endpoint.submitCall(42)
        }.assertIsOk()
    }
}

/**
 * Runs [block] once per seed in [seeds], each time under [withRandomizedDispatchOrder], so races between
 * concurrently-launched coroutines get shuffled differently on every run while staying in virtual time - the test
 * scheduler would otherwise always pick the same single interleaving. Fails with the offending seed attached, so a
 * failure can be reproduced by rerunning just that seed (e.g. `runTest2(seeds = 17L..17L) { ... }`).
 */
fun runTestWithRandomizedDispatchOrdering(seeds: LongRange = 0L until 30L, block: suspend CoroutineScope.() -> Unit) {
    for (seed in seeds) {
        try {
            kotlinx.coroutines.test.runTest(timeout = 1.seconds) {
                withRandomizedDispatchOrder(seed) { block() }
            }
        } catch (t: Throwable) {
            throw AssertionError("Failed with dispatch-order seed $seed", t)
        }
    }
}

private suspend fun UserDatabaseWithLegacyQueryUser.queryUser(id: Int): User {
    val isDone = CompletableDeferred<User>()
    val handle = queryUserWithCallback(
        id,
        onSuccess = { isDone.complete(it) },
        onError = { isDone.completeExceptionally(it) },
    )

    return try {
        isDone.await()
    } catch (t: Throwable) {
        if (!currentCoroutineContext().isActive) {
            handle.cancel(onCancellationFinished = { isDone.completeExceptionally(t) })
            importantCleanup {
                isDone.await()
            }
        }
        throw t
    }
}

private suspend fun UserDatabaseWithLegacyQueryUser.queryUserThatDoesntWaitForCancellationToComplete(id: Int): User =
    suspendCancellableCoroutine { cc ->
        val handle = queryUserWithCallback(
            id,
            onSuccess = { cc.resume(it) },
            onError = { cc.resumeWithException(it) },
        )
        cc.invokeOnCancellation {
            handle.cancel(onCancellationFinished = {})
        }
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

private suspend fun UserDatabaseWithLegacyQueryUser.queryUserHappyPath(id: Int): User {
    return suspendCancellableCoroutine { continuation ->
        queryUserWithCallback(id, onSuccess = { continuation.resume(it) })
    }
}

private fun CoroutinePuzzleSolutionResult.assertIsOk(): Unit = when (this) {
    is CoroutinePuzzleSolutionResult.Failure -> junitFail { this.toMessage() }
    CoroutinePuzzleSolutionResult.Success -> { /** All OK! */ }
}

private fun CoroutinePuzzleSolutionResult.assertIsNotOk(): CoroutinePuzzleSolutionResult.Failure =
    assertIs<CoroutinePuzzleSolutionResult.Failure> { "Puzzle succeeded unexpectedly" }

internal inline fun <reified T> Any?.assertIs(
    message: (Any?) -> String = { "Expected instance of ${T::class}, but got $it" },
): T = if (this is T) this else junitFail(message(this))

internal inline fun Any?.assertIs(other: Any?, message: (Any?) -> String) {
    assertEquals(this, message(this))
}

internal inline fun <T> T.assert(test: (T) -> Boolean, message: (T) -> String): T =
    this.also { if (!test(this)) kotlin.test.fail(message(this) + "\nActual value was: $this") }
