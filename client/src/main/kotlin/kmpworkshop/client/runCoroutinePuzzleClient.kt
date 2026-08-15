package kmpworkshop.client

import kmpworkshop.api.*
import kmpworkshop.common.*
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.*
import kotlinx.coroutines.CoroutineScope

data class CoroutinePuzzleWorkshopSolutions(
    val sumSolution: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
    val collectSolution: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
    val shipmentTrackingSolution: suspend CoroutineScope.(ShipmentTrackingApi) -> Unit,
    val maximumAgeFindingTheSecondCoroutineSolution: suspend CoroutineScope.(UserDatabase) -> Unit,
    val mappingLegacyApiCoroutineSolution: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
    val exceptionHandlingSolution: suspend CoroutineScope.(ExceptionalApi) -> Unit,
    val fileExposureSolution: suspend CoroutineScope.(FileToInternetExposingApi) -> Unit,
)

fun CoroutinePuzzleWorkshopSolutions.asSolution(stage: WorkshopStage.CoroutinePuzzleStage): CoroutinePuzzleSolution = {
    when (stage) {
        SumOfTwoIntsSlow,
        SumOfTwoIntsFast, -> sumSolution(getNumberAndSubmit())
        SumOfTwoIntsCancellation, -> sumWithLifecycleScaffolding(sumSolution)
        SumOfTwoIntsException,
        SumOfTwoIntsExceptionAfterCancellation -> sumWithLifecycleScaffolding(
            sumSolution,
            reportEscapedCancellation = true,
            cancelWhenLifetimeEnds = false,
            reportCancellationCompletion = true,
        )
        FindMaximumAgeCoroutines,
        FastFindMaximumAgeCoroutines, -> maximumAgeFindingTheSecondCoroutineSolution(getUserDatabase())
        MappingFromLegacyApisStepOne -> mappingLegacyApiCoroutineSolution(getUserDatabaseWithLegacyQueryUser(this))
        MappingFromLegacyApisStepTwo,
        MappingFromLegacyApisStepThree,
        MappingFromLegacyApisStepFour -> mapFromLegacyApiWithScaffolding(mappingLegacyApiCoroutineSolution)
        ExceptionCatchingWithCoroutines -> exceptionsInCoroutineHandlingScaffolding(exceptionHandlingSolution)
        SimpleFlow,
        CollectLatest, -> flowScaffolding(collectSolution)
        ShipmentTrackingIndependentViews,
        ShipmentTrackingSharedConnection,
        ShipmentTrackingLateEtaCard,
        ShipmentTrackingLazyConnection,
        ShipmentTrackingWhileSubscribed -> shipmentTrackingScaffolding(shipmentTrackingSolution)
        FileExposureStepOne,
        FileExposureStepTwo,
        FileExposureStepThree -> fileExposureScaffolding(fileExposureSolution)
    }
}

suspend fun runCoroutinePuzzleClient(
    puzzleProvider: CoroutinePuzzleProvider,
    stage: WorkshopStage.CoroutinePuzzleStage,
    solution: CoroutinePuzzleSolution,
): CoroutinePuzzleResultWithHistory =
    puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solveAsFlow(solution).toResultWithHistory()

private fun CoroutinePuzzle.wrappedWithImportantCleanup(): CoroutinePuzzle = CoroutinePuzzle { solution ->
    this@wrappedWithImportantCleanup.solveAsFlow {
        withImportantCleanup { solution() }
    }
}
