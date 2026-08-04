package com.kotlinworkshop.test

import kmpworkshop.client.exceptionHandlingPuzzle
import kmpworkshop.client.mapFromLegacyApi
import kmpworkshop.client.maximumAgeFindingWithCoroutines
import kmpworkshop.client.numberSummer
import kmpworkshop.client.runCoroutinePuzzleClient
import kmpworkshop.client.showingHowItsFlowing
import kmpworkshop.common.*
import kmpworkshop.common.WorkshopStage.*
import kmpworkshop.server.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import workshop.adminaccess.PuzzleState
import workshop.adminaccess.ScheduledWorkshopEvent
import workshop.adminaccess.ServerState
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertFails
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kmpworkshop.client.CoroutinePuzzleWorkshopSolutions as Solutions
import kmpworkshop.common.CoroutinePuzzleResultWithHistory as ResultsWHistory

class CoroutinePuzzleTestWithoutRpcService : WorkshopCoroutinePuzzleTest() {
    override suspend fun runCoroutinePuzzle(
        stage: WorkshopStage,
        solutions: Solutions,
    ): ResultsWHistory = runCoroutinePuzzleClient(
        puzzleProvider = { findCoroutinePuzzleFor(it).asPuzzle() },
        stage,
        solutions,
    )
}

@OptIn(ExperimentalTime::class)
suspend fun runTestClient(
    stage: WorkshopStage,
    solutions: Solutions,
): ResultsWHistory = coroutineScope {
    val serverState = MutableStateFlow(ServerState(puzzleStates = mapOf(
        stage.name to PuzzleState.Opened(Clock.System.now(), submissions = emptyMap()),
    )))
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
    val workshopService = workshopService(serverState, onEvent = { launch { eventBus.send(it) } })
        .asServer(ApiKey("1234-5678"))
    try {
        runCoroutinePuzzleClient(puzzleProvider = workshopService, stage, solutions)
    } finally {
        job.cancel()
        eventBus.close()
    }
}

class WorkshopCoroutinePuzzleTestWithRpcService : WorkshopCoroutinePuzzleTest() {
    override suspend fun runCoroutinePuzzle(stage: WorkshopStage, solutions: Solutions): ResultsWHistory =
        runTestClient(stage, solutions)
}

abstract class WorkshopCoroutinePuzzleTest: WorkshopCoroutinePuzzlesTestBase() {
    @Test
    fun `empty solutions are wrong`(): Unit = runPuzzleTest {
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
        doExceptionHandlingPuzzle { }.assertIsNotOk()
    }

    @Test
    fun `default implementations are wrong`(): Unit = runPuzzleTest {
        doSimpleSumPuzzle { numberSummer(it) }.assertIsNotOk()
        doTimedSumPuzzle { numberSummer(it) }.assertIsNotOk()
        doSimpleCollectPuzzle { showingHowItsFlowing(it) }.assertIsNotOk()
        doCollectLatestPuzzle { showingHowItsFlowing(it) }.assertIsNotOk()
        doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { maximumAgeFindingWithCoroutines(it) }.assertIsNotOk()
        doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { maximumAgeFindingWithCoroutines(it) }.assertIsNotOk()
        doMappingLegacyApiWithExceptionCoroutinePuzzle { mapFromLegacyApi(it) }.assertIsNotOk()
        doMappingLegacyApiWithCancellationCoroutinePuzzle { mapFromLegacyApi(it) }.assertIsNotOk()
        doMappingLegacyApiStepFourCoroutinePuzzle { mapFromLegacyApi(it) }.assertIsNotOk()
        doMappingLegacyApiHappyPathCoroutinePuzzle { mapFromLegacyApi(it) }.assertIsNotOk()
        doExceptionHandlingPuzzle { exceptionHandlingPuzzle(it) }.assertIsNotOk()
    }

    @Test
    fun `regular collect must fail collect latest puzzle`(): Unit = runPuzzleTest {
        doCollectLatestPuzzle { api ->
            api.numbers().collect { api.submit(it) }
        }
            .assertIsNotOk<CoroutinePuzzleSolutionResult.FullyQuiescent>()
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
    fun simpleSumCorrectSolution(): Unit = runPuzzleTest {
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
            api.submit(123123123)
        }.assertIsNotOk()
    }

    @Test
    fun `submitting in parallel is ok`(): Unit = runPuzzleTest {
        doSimpleSumPuzzle { api ->
            val firstSum = async { api.getNumber() }
            val asd = api.getNumber() + firstSum.await()
            api.submit(asd)
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
            .assertIsNotOk<CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure>()
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
        assertFails {
            doMappingLegacyApiWithExceptionCoroutinePuzzle { database ->
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
            .assertIsNotOk<CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure>()
    }

    @Test
    fun `handling exceptions around launches fails`(): Unit = runPuzzleTest {
        doExceptionHandlingPuzzle { api ->
            try {
                launch { api.clearCaches() }
                launch { api.refreshTokens() }
            } catch (e: Exception) {
                api.reportException(e)
            }
        }.assertIsNotOk()
    }

    @Test
    fun `handling exceptions around coroutineScope passes`(): Unit = runPuzzleTest {
        doExceptionHandlingPuzzle { api ->
            try {
                coroutineScope {
                    launch { api.clearCaches() }
                    launch { api.refreshTokens() }
                }
            } catch (e: Exception) {
                api.reportException(e)
            }
        }.assertIsOk()
    }

    @Test
    fun `running clear caches and refresh tokens synchronously fails`(): Unit = runPuzzleTest {
        doExceptionHandlingPuzzle { api ->
            try {
                api.clearCaches()
                api.refreshTokens()
            } catch (e: Exception) {
                api.reportException(e)
            }
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure>()
    }

    @Test
    fun `not handling refresh tokens exception fails`(): Unit = runPuzzleTest {
        doExceptionHandlingPuzzle { api ->
            launch { api.clearCaches() }
            launch { api.refreshTokens() }
        }.assertIsNotOk()
    }

    @Test
    fun `throwing different exception than original fails`(): Unit = runPuzzleTest {
        doExceptionHandlingPuzzle { api ->
            try {
                coroutineScope {
                    launch { api.clearCaches() }
                    launch { api.refreshTokens() }
                }
            } catch (e: Exception) {
                api.reportException(Exception(null, e))
            }
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
    }
}


abstract class WorkshopCoroutinePuzzlesTestBase {
    /**
     * How each transport-driven test body is run. Defaults to the virtual-time, randomized-dispatch harness, which
     * is the right choice for the in-process transports (it shuffles the single virtual-time interleaving across many
     * seeds). Transports whose ordering is decided by something virtual time can't touch - e.g. a real socket - should
     * override this to run once in real time instead, where the transport itself supplies the non-determinism.
     */
    protected open fun runPuzzleTest(block: suspend CoroutineScope.() -> Unit): Unit =
        runTestWithRandomizedDispatchOrdering(block = block)

    protected abstract suspend fun runCoroutinePuzzle(stage: WorkshopStage, solutions: Solutions): ResultsWHistory

    suspend fun doSimpleSumPuzzle(block: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(SumOfTwoIntsSlow, solutions(sumSolution = block))
    suspend fun doTimedSumPuzzle(block: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(SumOfTwoIntsFast, solutions(sumSolution = block))
    suspend fun doSimpleCollectPuzzle(block: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(SimpleFlow, solutions(collectSolution = block))
    suspend fun doCollectLatestPuzzle(block: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(CollectLatest, solutions(collectSolution = block))
    suspend fun doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle(block: suspend CoroutineScope.(UserDatabase) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(FindMaximumAgeCoroutines, solutions(maximumAgeFindingTheSecondCoroutineSolution = block))
    suspend fun doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle(block: suspend CoroutineScope.(UserDatabase) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(FastFindMaximumAgeCoroutines, solutions(maximumAgeFindingTheSecondCoroutineSolution = block))
    suspend fun doMappingLegacyApiWithExceptionCoroutinePuzzle(block: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(MappingFromLegacyApisStepTwo, solutions(mappingLegacyApiCoroutineSolution = block))
    suspend fun doMappingLegacyApiWithCancellationCoroutinePuzzle(block: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(MappingFromLegacyApisStepThree, solutions(mappingLegacyApiCoroutineSolution = block))
    suspend fun doMappingLegacyApiStepFourCoroutinePuzzle(block: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(MappingFromLegacyApisStepFour, solutions(mappingLegacyApiCoroutineSolution = block))
    suspend fun doMappingLegacyApiHappyPathCoroutinePuzzle(block: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(MappingFromLegacyApisStepOne, solutions(mappingLegacyApiCoroutineSolution = block))
    suspend fun doExceptionHandlingPuzzle(block: suspend CoroutineScope.(ExceptionalApi) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(ExceptionCatchingWithCoroutines, solutions(exceptionHandlingSolution = block))
}

/**
 * Runs [block] once per seed in [seeds], each time under [withRandomizedDispatchOrder], so races between
 * concurrently-launched coroutines get shuffled differently on every run while staying in virtual time - the test
 * scheduler would otherwise always pick the same single interleaving. Fails with the offending seed attached, so a
 * failure can be reproduced by rerunning just that seed
 * (e.g. `doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle(seeds = 17L..17L) { ... }`).
 */
fun runTestWithRandomizedDispatchOrdering(seeds: LongRange = 0L until 100L, block: suspend CoroutineScope.() -> Unit) {
    for (seed in seeds) {
        try {
            runTest(timeout = 1.seconds) {
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
