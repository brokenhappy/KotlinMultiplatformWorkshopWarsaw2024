package com.kotlinworkshop.test

import kmpworkshop.client.runCoroutinePuzzleClient
import kmpworkshop.common.*
import kmpworkshop.server.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import workshop.adminaccess.PuzzleState
import workshop.adminaccess.ScheduledWorkshopEvent
import workshop.adminaccess.ServerState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertEquals
import kotlin.time.Clock
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
    doMappingLegacyApiCoroutinePuzzle = ::doMappingLegacyApiCoroutinePuzzle,
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
    doMappingLegacyApiCoroutinePuzzle = {
        runTestClient(stage = WorkshopStage.MappingFromLegacyApisStepOne, mappingLegacyApiCoroutineSolution = it)
    },
    doMappingLegacyApiWithCancellationCoroutinePuzzle = {
        runTestClient(stage = WorkshopStage.MappingFromLegacyApisStepTwo, mappingLegacyApiCoroutineSolution = it)
    },
    doMappingLegacyApiWithExceptionCoroutinePuzzle = {
        runTestClient(stage = WorkshopStage.MappingFromLegacyApisStepThree, mappingLegacyApiCoroutineSolution = it)
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
    private val doMappingLegacyApiCoroutinePuzzle: DoPuzzleWith<UserDatabaseWithLegacyQueryUser>,
) {
    @Test
    fun `empty solutions are wrong`(): Unit = runTest {
        doSimpleSumPuzzle { }.assertIsNotOk()
        doTimedSumPuzzle { }.assertIsNotOk()
        doSimpleCollectPuzzle { }.assertIsNotOk()
        doCollectLatestPuzzle { }.assertIsNotOk()
        doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { }.assertIsNotOk()
        doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { }.assertIsNotOk()
        doMappingLegacyApiWithExceptionCoroutinePuzzle { }.assertIsNotOk()
        doMappingLegacyApiWithCancellationCoroutinePuzzle { }.assertIsNotOk()
        doMappingLegacyApiCoroutinePuzzle { }.assertIsNotOk()
    }

    @Test
    fun `regular collect must fail collect latest puzzle`(): Unit = runTest {
        doCollectLatestPuzzle { api ->
            api.numbers().collect { api.submit(it) }
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "Regular collect must fail collect latest puzzle" }
            .description
            .assert({ "cancel" in it.lowercase() }) { "Message must mention cancellation" }
            .assert({ "submit" in it.lowercase() }) { "Message must mention submit" }
    }

    @Test
    fun `collectLatest correct solution`(): Unit = runTest {
        doCollectLatestPuzzle { api ->
            api.numbers().collectLatest {
                api.submit(it)
            }
        }.assertIsOk()
    }

    @Test
    fun `simple flow puzzle does not need collect latest`(): Unit = runTest {
        doSimpleCollectPuzzle { api ->
            api.numbers().collect { api.submit(it) }
        }.assertIsOk()
    }

    @Test
    fun `simple flow puzzle might pass with collect latest`(): Unit = runTest {
        // Not strictly needed behavior, but I keep it in here to increase coverage
        doSimpleCollectPuzzle { api ->
            api.numbers().collectLatest { api.submit(it) }
        }.assertIsOk()
    }

    @Test
    fun `simple sum correct solution`(): Unit = runTest {
        doSimpleSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber())
        }.assertIsOk()
    }

    @Test
    fun `sum of too many numbers`(): Unit = runTest {
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
    fun `submitting incorrect sum is not ok`(): Unit = runTest {
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
    fun `submitting in parallel is ok`(): Unit = runTest {
        doSimpleSumPuzzle { api ->
            val firstSum = async { api.getNumber() }
            api.submit(api.getNumber() + firstSum.await())
        }.assertIsOk()
    }

    @Test
    fun `timed sum correct solution`(): Unit = runTest {
        doTimedSumPuzzle { api ->
            val firstSum = async { api.getNumber() }
            api.submit(api.getNumber() + firstSum.await())
        }.assertIsOk()
    }

    @Test
    fun `timed sum too slow solution fails`(): Unit = runTest {
        doTimedSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber())
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "Regular collect must fail collect latest puzzle" }
            .description
            .assert({ "slow" in it.lowercase() }) { "Message must mention it being slow" }
            .assert({ "1.8" in it.lowercase() }) { "Message must mention expected time" }
    }

    @Test
    fun `correct simple maximum age finding solution`(): Unit = runTest {
        doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .maxOf { database.queryUser(it).age }
            )
        }.assertIsOk()
    }

    @Test
    fun `correct timed maximum age finding solution`(): Unit = runTest {
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
    fun `simple maximum age finding solution should also be solvable in parallel`(): Unit = runTest {
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
    fun `simple legacy api solution works without exception and cancellation handling`(): Unit = runTest {
        doMappingLegacyApiCoroutinePuzzle { database ->
            database.submit(
                database
                    .getAllIds()
                    .maxOf { database.queryUserWithoutException(it).age }
            )
        }.assertIsOk()
    }

    @Test
    fun `simple legacy api solution without exception and cancellation handling works in parallel too`(): Unit = runTest {
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
    fun `solution without exceptions does not work for the legacy mapping with exceptions puzzle`(): Unit = runTest {
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
    fun `solution with exceptions but without cancellation does work for the legacy mapping with exceptions puzzle`(): Unit = runTest {
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
    fun `full solution is correct for last puzzle`(): Unit = runTest {
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
    fun `synchronous solution for timed maximum age finding fails`(): Unit = runTest {
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

    @Test
    fun `internal calls are NOT shown in history of error message`() = runTest {
        val internalEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("internal", isHiddenInHistory = true)
        val publicEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("public", isHiddenInHistory = false)
        coroutinePuzzle {
            internalEndpoint.expectCall(Unit)
        }.solve {
            internalEndpoint.submitCall(Unit)
            publicEndpoint.submitCall(Unit) // Should result in error
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure>()
            .description
            .assert({ "internal" !in it.lowercase() }) { "Message must not mention internal endpoint" }
    }

    @Test
    fun `internal calls ARE shown in expected calls part of error message`() = runTest {
        val internalEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("internal", isHiddenInHistory = true)
        val publicEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("public", isHiddenInHistory = false)
        coroutinePuzzle {
            publicEndpoint.expectCall(Unit)
        }.solve {
            internalEndpoint.submitCall(Unit)
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure>()
            .description
            .assert({ "internal" in it.lowercase() }) { "Message must mention internal endpoint" }
    }
}

fun runTest(block: suspend CoroutineScope.() -> Unit) = kotlinx.coroutines.test.runTest(timeout = 1.seconds) { block() }

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

internal inline fun <reified T> Any?.assertIs(
    message: (Any?) -> String = { "Expected instance of ${T::class}, but got $it" },
): T =
    if (this is T) this else kotlin.test.fail(message(this))

internal inline fun Any?.assertIs(other: Any?, message: (Any?) -> String) {
    assertEquals(this, message(this))
}

internal inline fun <T> T.assert(test: (T) -> Boolean, message: (T) -> String): T =
    this.also { if (!test(this)) kotlin.test.fail(message(this) + "\nActual value was: $this") }
