package com.kotlinworkshop.test

import kmpworkshop.client.runCoroutinePuzzleClient
import kmpworkshop.client.workshopSolutions
import kmpworkshop.api.*
import kmpworkshop.solutions.allowPeopleToDownloadExposedFile
import kmpworkshop.common.*
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.*
import kmpworkshop.server.CoroutinePuzzleErrorMessages
import kmpworkshop.server.findCoroutinePuzzleFor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import testWorkshopService
import workshop.adminaccess.PuzzleState
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
        stage: CoroutinePuzzleStage,
        solutions: Solutions,
    ): ResultsWHistory = runCoroutinePuzzleClient(
        puzzleProvider = { findCoroutinePuzzleFor(it).asPuzzle() },
        stage,
        solutions,
    )
}

@OptIn(ExperimentalTime::class)
suspend fun runTestClient(
    stage: CoroutinePuzzleStage,
    solutions: Solutions,
): ResultsWHistory = coroutineScope {
    testWorkshopService(serverStateThatOpened(stage)).use { (service) ->
        runCoroutinePuzzleClient(puzzleProvider = service.asServer(ApiKey("1234-5678")), stage, solutions)
    }
}

class WorkshopCoroutinePuzzleTestWithRpcService : WorkshopCoroutinePuzzleTest() {
    override suspend fun runCoroutinePuzzle(stage: CoroutinePuzzleStage, solutions: Solutions): ResultsWHistory =
        runTestClient(stage, solutions)
}

abstract class WorkshopCoroutinePuzzleTest: WorkshopCoroutinePuzzlesTestBase() {
    @Test
    fun `empty solutions are wrong`(): Unit = runPuzzleTest {
        val emptySolutions = Solutions(
            sumSolution = {},
            collectSolution = {},
            maximumAgeFindingTheSecondCoroutineSolution = {},
            mappingLegacyApiCoroutineSolution = {},
            exceptionHandlingSolution = {},
            fileExposureSolution = {}
        )
        CoroutinePuzzleStage.entries.forEach { stage -> runCoroutinePuzzle(stage, emptySolutions).assertIsNotOk() }
    }

    @Test
    fun `default implementations are wrong`(): Unit = runPuzzleTest {
        CoroutinePuzzleStage.entries.forEach { stage -> runCoroutinePuzzle(stage, workshopSolutions).assertIsNotOk() }
    }

    @Test
    fun `first solution should not work for first file exposure puzzle`(): Unit = runPuzzleTest {
        // Step 1: a weak -> strong transition must wait for cancellation of the previous network task.
        // The workshop scaffold reacts from a non-suspending callback, so it cancels without joining before restart.
        doFileExposureStepOne { allowPeopleToDownloadExposedFile(it) }
            .assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.networkRestartStartedTooEarly(
                listOf(makeFileDownloadable, advertiseExposedFile),
            ))
    }

    @Test
    fun `second solution should work for first file exposure puzzle`(): Unit = runPuzzleTest {
        doFileExposureStepOne { allowPeopleToDownloadExposedFile2(it) }.assertIsOk()
    }

    @Test
    fun `file exposure solutions progress one lesson at a time`(): Unit = runPuzzleTest {
        // Step 2: replacing a file must cancel and join that file's download work.
        // Solution 2 observes network strength in the API scope, so replacing the file cannot cancel it.
        doFileExposureStepOne { allowPeopleToDownloadExposedFile2(it) }.assertIsOk()
        doFileExposureStepTwo { allowPeopleToDownloadExposedFile2(it) }
            .assertIsNotOk<CoroutinePuzzleSolutionResult.FullyQuiescent>()
        doFileExposureStepThree { allowPeopleToDownloadExposedFile2(it) }.assertIsNotOk()

        // Step 3: advertising must be joined as well. Solution 3 fixes the download lifetime, but its launch still
        // captures the outer solution scope and lets advertising escape the current strong-network task.
        doFileExposureStepOne { allowPeopleToDownloadExposedFile3(it) }.assertIsOk()
        doFileExposureStepTwo { allowPeopleToDownloadExposedFile3(it) }.assertIsOk()
        val advertisingFailure = doFileExposureStepThree { allowPeopleToDownloadExposedFile3(it) }
            .assertIsNotOk<CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure>()
        advertisingFailure.expectations.assertEquals(emptyList<CoroutinePuzzleEndPointDescriptor>())
        advertisingFailure.unexpectedSubmissions.assertEquals(
            listOf(emitNetworkStrength.descriptor, closeExposedFile.descriptor),
        )

        // Solution 4 gives the task a lexical coroutineScope receiver; both download and advertising are children.
        doFileExposureStepOne { allowPeopleToDownloadExposedFile4(it) }.assertIsOk()
        doFileExposureStepTwo { allowPeopleToDownloadExposedFile4(it) }.assertIsOk()
        doFileExposureStepThree { allowPeopleToDownloadExposedFile4(it) }.assertIsOk()
    }

    @Test
    fun `collect cannot replace a file while its work is active`(): Unit = runPuzzleTest {
        doFileExposureStepOne { allowPeopleToDownloadExposedFileWithCollect(it) }.assertIsNotOk()
        doFileExposureStepTwo { allowPeopleToDownloadExposedFileWithCollect(it) }.assertIsNotOk()
    }

    @Test
    fun `advertising sequentially never reaches downloading`(): Unit = runPuzzleTest {
        doFileExposureStepOne { allowPeopleToDownloadExposedFileWithSequentialAdvertising(it) }.assertIsNotOk()
    }

    @Test
    fun `exposing a file on weak WiFi gives specific guidance`(): Unit = runPuzzleTest {
        doFileExposureStepOne { api ->
            api.currentFileToExpose().collectLatest { file ->
                file.open()
                api.makeDownloadable(file)
            }
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.weakWifiExposureStarted())
    }

    @Test
    fun `using the previous file for replacement work gives specific guidance`(): Unit = runPuzzleTest {
        var firstFile: FakeFile? = null
        doFileExposureStepTwo { api ->
            api.currentFileToExpose().collectLatest { file ->
                file.open()
                try {
                    val downloadFile = firstFile ?: file.also { firstFile = it }
                    api.runOnStrongNetwork4 {
                        launch { api.advertiseFile(file) }
                        try {
                            api.makeDownloadable(downloadFile)
                        } catch (_: ExceptionAcrossRpc) {
                            awaitCancellation()
                        }
                    }
                } finally {
                    file.close()
                }
            }
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.wrongFile("make downloadable", "the replacement file"))
    }

    @Test
    fun `opening the previous file after replacement gives specific guidance`(): Unit = runPuzzleTest {
        var firstFile: FakeFile? = null
        val solved = doFileExposureStepTwo { api ->
            api.currentFileToExpose().collectLatest { file ->
                val fileToOpen = firstFile ?: file.also { firstFile = it }
                fileToOpen.open()
                try {
                    api.runOnStrongNetwork4 {
                        launch { api.advertiseFile(file) }
                        api.makeDownloadable(file)
                    }
                } finally {
                    file.close()
                }
            }
        }
        val expected = solved.returnedValues(emitFileToExpose).last()
        val actual = solved.arguments(openExposedFile).last()
        solved.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.wrongEndpointArgument(expected, actual))
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
    fun `submitting the wrong flow value gives specific guidance`(): Unit = runPuzzleTest {
        var emitted = 0
        doSimpleCollectPuzzle { api ->
            api.numbers().collect {
                emitted = it
                api.submit(it + 1)
            }
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.wrongFlowValue(emitted + 1, emitted))
    }

    @Test
    fun `submitting the wrong collectLatest value gives specific guidance`(): Unit = runPuzzleTest {
        var submitted = 0
        doCollectLatestPuzzle { api ->
            api.numbers().collectLatest {
                submitted = it + 1
                api.submit(submitted)
            }
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.wrongFlowValue(submitted, submitted - 1))
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
    fun `incorrect sum gives specific guidance`(): Unit = runPuzzleTest {
        val numbers = mutableListOf<Int>()
        doSimpleSumPuzzle { api ->
            numbers += api.getNumber()
            numbers += api.getNumber()
            api.submit(numbers.sum() + 1)
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.incorrectSum(numbers, numbers.sum() + 1))
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
            .assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.sumCallsMustBeConcurrent())
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
    fun `incorrect oldest age gives specific guidance`(): Unit = runPuzzleTest {
        var oldest = 0
        doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { database ->
            oldest = database.getAllIds().maxOf { database.queryUser(it).age }
            database.submit(oldest - 1)
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.wrongOldestAge(oldest - 1, oldest))
    }

    @Test
    fun `querying an unknown user gives specific guidance`(): Unit = runPuzzleTest {
        doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle { database ->
            database.getAllIds()
            try {
                database.queryUser(Int.MIN_VALUE)
            } catch (_: ExceptionAcrossRpc) {
                awaitCancellation()
            }
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.unknownUser(Int.MIN_VALUE))
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
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.cancellationMustFinishFirst())
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
            .assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.userQueriesMustBeConcurrent())
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
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.exceptionCallsMustBeConcurrent())
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
        var originalMessage: String? = null
        doExceptionHandlingPuzzle { api ->
            try {
                coroutineScope {
                    launch { api.clearCaches() }
                    launch { api.refreshTokens() }
                }
            } catch (e: Exception) {
                originalMessage = e.message
                api.reportException(Exception(null, e))
            }
        }.assertIsNotOk<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals(CoroutinePuzzleErrorMessages.wrongReportedException(requireNotNull(originalMessage), null))
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

    protected abstract suspend fun runCoroutinePuzzle(stage: CoroutinePuzzleStage, solutions: Solutions): ResultsWHistory

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
    suspend fun doFileExposureStepOne(block: suspend CoroutineScope.(FileToInternetExposingApi) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(FileExposureStepOne, solutions(fileExposureSolution = block))
    suspend fun doFileExposureStepTwo(block: suspend CoroutineScope.(FileToInternetExposingApi) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(FileExposureStepTwo, solutions(fileExposureSolution = block))
    suspend fun doFileExposureStepThree(block: suspend CoroutineScope.(FileToInternetExposingApi) -> Unit): ResultsWHistory =
        runCoroutinePuzzle(FileExposureStepThree, solutions(fileExposureSolution = block))
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

private inline fun <reified T> ResultsWHistory.returnedValues(
    endpoint: CoroutinePuzzleEndPoint<*, T>,
): List<T> {
    val callIds = history.filterIsInstance<CoroutinePuzzleHistoryBatch.Submission>()
        .flatMap { it.entries }
        .mapNotNull { entry ->
            (entry.payload as? CoroutinePuzzleBatchEntry.SubmissionPayload.CallSubmitted)
                ?.takeIf { it.endPoint == endpoint.descriptor }
                ?.let { entry.callId }
        }
        .toSet()
    return history.filterIsInstance<CoroutinePuzzleHistoryBatch.Expectation>()
        .flatMap { it.entries }
        .filter { it.callId in callIds }
        .mapNotNull { it.payload as? CoroutinePuzzleBatchEntry.ExpectationPayload.CallAnswered }
        .map { Json.decodeFromJsonElement(serializer<T>(), it.result) }
}

private inline fun <reified T> ResultsWHistory.arguments(
    endpoint: CoroutinePuzzleEndPoint<T, *>,
): List<T> = history.filterIsInstance<CoroutinePuzzleHistoryBatch.Submission>()
    .flatMap { it.entries }
    .mapNotNull { it.payload as? CoroutinePuzzleBatchEntry.SubmissionPayload.CallSubmitted }
    .filter { it.endPoint == endpoint.descriptor }
    .map { Json.decodeFromJsonElement(serializer<T>(), it.arg) }
