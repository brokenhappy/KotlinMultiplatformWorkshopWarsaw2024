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

suspend fun runCoroutinePuzzleClient(
    puzzleProvider: CoroutinePuzzleProvider,
    stage: WorkshopStage,
    sumSolution: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
    collectSolution: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
    maximumAgeFindingTheSecondCoroutineSolution: suspend CoroutineScope.(UserDatabase) -> Unit,
    mappingLegacyApiCoroutineSolution: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
    exceptionHandlingSolution: suspend CoroutineScope.(ExceptionalApi) -> Unit,
): CoroutinePuzzleResultWithHistory = when (stage) {
    Registration,
    PalindromeCheckTask,
    FindMinimumAgeOfUserTask,
    FindOldestUserTask -> error("Should never happen!")
    SumOfTwoIntsSlow,
    SumOfTwoIntsFast -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        sumSolution(getNumberAndSubmit())
    }
    FindMaximumAgeCoroutines,
    FastFindMaximumAgeCoroutines -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        maximumAgeFindingTheSecondCoroutineSolution(getUserDatabase())
    }
    MappingFromLegacyApisStepOne -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        mappingLegacyApiCoroutineSolution(getUserDatabaseWithLegacyQueryUser(this))
    }
    MappingFromLegacyApisStepTwo,
    MappingFromLegacyApisStepThree,
    MappingFromLegacyApisStepFour -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        mapFromLegacyApiWithScaffolding(mappingLegacyApiCoroutineSolution)
    }
    ExceptionCatchingWithCoroutines -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        exceptionsInCoroutineHandlingScaffolding(exceptionHandlingSolution)
    }
    SimpleFlow,
    CollectLatest -> puzzleProvider.coroutinePuzzle(stage).wrappedWithImportantCleanup().solve {
        collectSolution(numberFlowAndSubmit())
    }
}

private fun CoroutinePuzzle.wrappedWithImportantCleanup(): CoroutinePuzzle = CoroutinePuzzle {
    withImportantCleanup {
        this@wrappedWithImportantCleanup.solve(it)
    }
}