package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.CoroutinePuzzleProvider
import kmpworkshop.common.CoroutinePuzzleResultWithHistory
import kmpworkshop.common.ExceptionalApi
import kmpworkshop.common.GetNumberAndSubmit
import kmpworkshop.common.NumberFlowAndSubmit
import kmpworkshop.common.UserDatabase
import kmpworkshop.common.UserDatabaseWithLegacyQueryUser
import kmpworkshop.common.WorkshopStage
import kmpworkshop.common.WorkshopStage.*
import kmpworkshop.common.getNumberAndSubmit
import kmpworkshop.common.getUserDatabase
import kmpworkshop.common.getUserDatabaseWithLegacyQueryUser
import kmpworkshop.common.numberFlowAndSubmit
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CoroutineScope

data class CoroutinePuzzleWorkshopSolutions(
    val sumSolution: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
    val collectSolution: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
    val maximumAgeFindingTheSecondCoroutineSolution: suspend CoroutineScope.(UserDatabase) -> Unit,
    val mappingLegacyApiCoroutineSolution: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
    val exceptionHandlingSolution: suspend CoroutineScope.(ExceptionalApi) -> Unit,
)

suspend fun runCoroutinePuzzleClient(
    puzzleProvider: CoroutinePuzzleProvider,
    stage: WorkshopStage,
    solutions: CoroutinePuzzleWorkshopSolutions,
): CoroutinePuzzleResultWithHistory = when (stage) {
    Registration,
    PalindromeCheckTask,
    FindMinimumAgeOfUserTask,
    FindOldestUserTask -> error("Should never happen!")
    SumOfTwoIntsSlow,
    SumOfTwoIntsFast -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        solutions.sumSolution(this, getNumberAndSubmit())
    }
    FindMaximumAgeCoroutines,
    FastFindMaximumAgeCoroutines -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        solutions.maximumAgeFindingTheSecondCoroutineSolution(this, getUserDatabase())
    }
    MappingFromLegacyApisStepOne -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        solutions.mappingLegacyApiCoroutineSolution(this, getUserDatabaseWithLegacyQueryUser(this))
    }
    MappingFromLegacyApisStepTwo,
    MappingFromLegacyApisStepThree,
    MappingFromLegacyApisStepFour -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        mapFromLegacyApiWithScaffolding(solutions.mappingLegacyApiCoroutineSolution)
    }
    ExceptionCatchingWithCoroutines -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        exceptionsInCoroutineHandlingScaffolding(solutions.exceptionHandlingSolution)
    }
    SimpleFlow,
    CollectLatest -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        solutions.collectSolution(this, numberFlowAndSubmit())
    }
}

private fun CoroutinePuzzle.wrappedWithImportantCleanup(): CoroutinePuzzle = CoroutinePuzzle {
    withImportantCleanup {
        this@wrappedWithImportantCleanup.solve(it)
    }
}