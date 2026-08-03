package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleProvider
import kmpworkshop.common.CoroutinePuzzleResultWithHistory
import kmpworkshop.common.ExceptionalApi
import kmpworkshop.common.GetNumberAndSubmit
import kmpworkshop.common.NumberFlowAndSubmit
import kmpworkshop.common.UserDatabase
import kmpworkshop.common.UserDatabaseWithLegacyQueryUser
import kmpworkshop.common.WorkshopStage
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
    WorkshopStage.Registration,
    WorkshopStage.PalindromeCheckTask,
    WorkshopStage.FindMinimumAgeOfUserTask,
    WorkshopStage.FindOldestUserTask -> error("Should never happen!")
    WorkshopStage.SumOfTwoIntsSlow,
    WorkshopStage.SumOfTwoIntsFast -> withImportantCleanup {
        puzzleProvider.coroutinePuzzle(stage).solve {
            sumSolution(getNumberAndSubmit())
        }
    }
    WorkshopStage.FindMaximumAgeCoroutines,
    WorkshopStage.FastFindMaximumAgeCoroutines -> withImportantCleanup {
        puzzleProvider.coroutinePuzzle(stage).solve {
            maximumAgeFindingTheSecondCoroutineSolution(getUserDatabase())
        }
    }
    WorkshopStage.MappingFromLegacyApisStepOne -> withImportantCleanup {
        puzzleProvider.coroutinePuzzle(stage).solve {
            mappingLegacyApiCoroutineSolution(getUserDatabaseWithLegacyQueryUser(this))
        }
    }
    WorkshopStage.MappingFromLegacyApisStepTwo,
    WorkshopStage.MappingFromLegacyApisStepThree,
    WorkshopStage.MappingFromLegacyApisStepFour -> withImportantCleanup {
        puzzleProvider.coroutinePuzzle(stage).solve {
            mapFromLegacyApiWithScaffolding(mappingLegacyApiCoroutineSolution)
        }
    }
    WorkshopStage.ExceptionCatchingWithCoroutines -> withImportantCleanup {
        puzzleProvider.coroutinePuzzle(stage).solve {
            exceptionsInCoroutineHandlingScaffolding(exceptionHandlingSolution)
        }
    }
    WorkshopStage.SimpleFlow,
    WorkshopStage.CollectLatest -> withImportantCleanup {
        puzzleProvider.coroutinePuzzle(stage).solve {
            collectSolution(numberFlowAndSubmit())
        }
    }
}