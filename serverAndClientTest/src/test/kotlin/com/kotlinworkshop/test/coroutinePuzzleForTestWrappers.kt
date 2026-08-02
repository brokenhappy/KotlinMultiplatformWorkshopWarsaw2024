package com.kotlinworkshop.test

import kmpworkshop.client.exceptionsInCoroutineHandlingScaffolding
import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.CoroutinePuzzleResultWithHistory
import kmpworkshop.common.GetNumberAndSubmit
import kmpworkshop.common.NumberFlowAndSubmit
import kmpworkshop.common.UserDatabase
import kmpworkshop.common.UserDatabaseWithLegacyQueryUser
import kmpworkshop.common.getNumberAndSubmit
import kmpworkshop.common.getUserDatabase
import kmpworkshop.common.getUserDatabaseWithLegacyQueryUser
import kmpworkshop.client.mapFromLegacyApiWithScaffolding
import kmpworkshop.common.ExceptionalApi
import kmpworkshop.common.coroutineExceptionHandlingApiService
import kmpworkshop.common.numberFlowAndSubmit
import kmpworkshop.common.solve
import kmpworkshop.common.withImportantCleanup
import kmpworkshop.server.collectLatestPuzzle
import kmpworkshop.server.coroutineExceptionHandlingCoroutinePuzzle
import kmpworkshop.server.mappingLegacyApiCoroutinePuzzleStepFour
import kmpworkshop.server.mappingLegacyApiCoroutinePuzzleWithEscapingCancellation
import kmpworkshop.server.mappingLegacyApiCoroutinePuzzleWithException
import kmpworkshop.server.mappingLegacyApiHappyPathCoroutinePuzzle
import kmpworkshop.server.maximumAgeFindingTheSecondCoroutinePuzzle
import kmpworkshop.server.simpleFlowPuzzle
import kmpworkshop.server.simpleSumPuzzle
import kmpworkshop.server.timedSumPuzzle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope


suspend fun doMappingLegacyApiHappyPathCoroutinePuzzle(
    onUse: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleResultWithHistory = mappingLegacyApiHappyPathCoroutinePuzzle().solve {
    val scope = this
    withImportantCleanup {
        onUse(getUserDatabaseWithLegacyQueryUser(topLevelScope = scope))
    }
}

suspend fun doMappingLegacyApiWithExceptionCoroutinePuzzle(
    onUse: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleResultWithHistory = mappingLegacyApiCoroutinePuzzleWithException().solve {
    mapFromLegacyApiWithScaffolding {
        coroutineScope {
            onUse(it)
        }
    }
}

suspend fun doMappingLegacyApiWithCancellationCoroutinePuzzle(
    onUse: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleResultWithHistory = mappingLegacyApiCoroutinePuzzleWithEscapingCancellation().solve {
    mapFromLegacyApiWithScaffolding {
        coroutineScope {
            onUse(it)
        }
    }
}

suspend fun doMappingLegacyApiStepFourCoroutinePuzzle(
    onUse: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleResultWithHistory = mappingLegacyApiCoroutinePuzzleStepFour().solve {
    mapFromLegacyApiWithScaffolding {
        coroutineScope {
            onUse(it)
        }
    }
}

suspend fun doSimpleMaximumAgeFindingTheSecondCoroutinePuzzle(onUse: suspend CoroutineScope.(UserDatabase) -> Unit): CoroutinePuzzleResultWithHistory =
    doUserDatabasePuzzle(maximumAgeFindingTheSecondCoroutinePuzzle(mustBeConcurrent = false), onUse)

suspend fun doTimedSimpleMaximumAgeFindingTheSecondCoroutinePuzzle(onUse: suspend CoroutineScope.(UserDatabase) -> Unit): CoroutinePuzzleResultWithHistory =
    doUserDatabasePuzzle(maximumAgeFindingTheSecondCoroutinePuzzle(mustBeConcurrent = true), onUse)

private suspend fun doUserDatabasePuzzle(
    puzzle: CoroutinePuzzle,
    onUse: suspend CoroutineScope.(UserDatabase) -> Unit,
): CoroutinePuzzleResultWithHistory = puzzle.solve {
    withImportantCleanup {
        onUse(getUserDatabase())
    }
}

suspend fun doCollectLatestPuzzle(
    onUse: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
): CoroutinePuzzleResultWithHistory = doFlowAndSubmitPuzzle(collectLatestPuzzle(), onUse)

suspend fun doSimpleCollectPuzzle(
    onUse: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
): CoroutinePuzzleResultWithHistory = doFlowAndSubmitPuzzle(simpleFlowPuzzle(), onUse)

private suspend fun doFlowAndSubmitPuzzle(
    puzzle: CoroutinePuzzle,
    onUse: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
): CoroutinePuzzleResultWithHistory = puzzle.solve {
    withImportantCleanup {
        onUse(numberFlowAndSubmit())
    }
}

suspend fun doSimpleSumPuzzle(
    onUse: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
): CoroutinePuzzleResultWithHistory = doSumPuzzle(simpleSumPuzzle(), onUse)

suspend fun doTimedSumPuzzle(
    onUse: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
): CoroutinePuzzleResultWithHistory = doSumPuzzle(timedSumPuzzle(), onUse)

private suspend fun doSumPuzzle(
    puzzle: CoroutinePuzzle,
    onUse: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
): CoroutinePuzzleResultWithHistory = puzzle.solve {
    withImportantCleanup {
        onUse(getNumberAndSubmit())
    }
}

suspend fun doCoroutineExceptionHandlingCoroutinePuzzle(
    onUse: suspend CoroutineScope.(ExceptionalApi) -> Unit,
): CoroutinePuzzleResultWithHistory = coroutineExceptionHandlingCoroutinePuzzle().solve {
    exceptionsInCoroutineHandlingScaffolding { coroutineScope { onUse(it) } }
}
