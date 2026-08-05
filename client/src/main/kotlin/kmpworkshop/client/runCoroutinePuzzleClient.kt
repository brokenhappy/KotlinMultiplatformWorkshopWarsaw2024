package kmpworkshop.client

import kmpworkshop.api.*
import kmpworkshop.common.*
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

data class CoroutinePuzzleWorkshopSolutions(
    val sumSolution: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
    val collectSolution: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
    val maximumAgeFindingTheSecondCoroutineSolution: suspend CoroutineScope.(UserDatabase) -> Unit,
    val mappingLegacyApiCoroutineSolution: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
    val exceptionHandlingSolution: suspend CoroutineScope.(ExceptionalApi) -> Unit,
    val fileExposureSolution: suspend CoroutineScope.(FileToInternetExposingApi) -> Unit,
)

suspend fun runCoroutinePuzzleClient(
    puzzleProvider: CoroutinePuzzleProvider,
    stage: WorkshopStage.CoroutinePuzzleStage,
    solutions: CoroutinePuzzleWorkshopSolutions,
): CoroutinePuzzleResultWithHistory =
    runCoroutinePuzzleClientAsFlow(puzzleProvider, stage, solutions).toResultWithHistory()

fun runCoroutinePuzzleClientAsFlow(
    puzzleProvider: CoroutinePuzzleProvider,
    stage: WorkshopStage.CoroutinePuzzleStage,
    solutions: CoroutinePuzzleWorkshopSolutions,
): Flow<CoroutinePuzzleSolveState> = when (stage) {
    SumOfTwoIntsSlow,
    SumOfTwoIntsFast -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solveAsFlow {
        solutions.sumSolution(this, getNumberAndSubmit())
    }
    FindMaximumAgeCoroutines,
    FastFindMaximumAgeCoroutines -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solveAsFlow {
        solutions.maximumAgeFindingTheSecondCoroutineSolution(this, getUserDatabase())
    }
    MappingFromLegacyApisStepOne -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solveAsFlow {
        solutions.mappingLegacyApiCoroutineSolution(this, getUserDatabaseWithLegacyQueryUser(this))
    }
    MappingFromLegacyApisStepTwo,
    MappingFromLegacyApisStepThree,
    MappingFromLegacyApisStepFour -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solveAsFlow {
        mapFromLegacyApiWithScaffolding(solutions.mappingLegacyApiCoroutineSolution)
    }
    ExceptionCatchingWithCoroutines -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solveAsFlow {
        exceptionsInCoroutineHandlingScaffolding(solutions.exceptionHandlingSolution)
    }
    SimpleFlow,
    CollectLatest -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solveAsFlow {
        solutions.collectSolution(this, numberFlowAndSubmit())
    }
    FileExposureStepOne,
    FileExposureStepTwo,
    FileExposureStepThree -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solveAsFlow {
        fileExposureScaffolding(solutions.fileExposureSolution)
    }
}

private fun CoroutinePuzzle.wrappedWithImportantCleanup(): CoroutinePuzzle = CoroutinePuzzle { solution ->
    this@wrappedWithImportantCleanup.solveAsFlow {
        withImportantCleanup { solution() }
    }
}
