package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.WorkshopStage

fun findCoroutinePuzzleFor(stage: WorkshopStage): CoroutinePuzzle? = when (stage) {
    WorkshopStage.Registration,
    WorkshopStage.PalindromeCheckTask,
    WorkshopStage.FindMinimumAgeOfUserTask,
    WorkshopStage.FindOldestUserTask,
    WorkshopStage.SumOfTwoIntsSlow -> simpleSumPuzzle()
    WorkshopStage.SumOfTwoIntsFast -> timedSumPuzzle()
    WorkshopStage.MappingFromLegacyApisStepOne,
    WorkshopStage.FindMaximumAgeCoroutines -> maximumAgeFindingTheSecondCoroutinePuzzle(isTimed = false)
    WorkshopStage.FastFindMaximumAgeCoroutines -> maximumAgeFindingTheSecondCoroutinePuzzle(isTimed = true)
    WorkshopStage.MappingFromLegacyApisStepTwo -> mappingLegacyApiCoroutinePuzzleWithException()
    WorkshopStage.MappingFromLegacyApisStepThree -> mappingLegacyApiCoroutinePuzzleWithCancellation()
    WorkshopStage.SimpleFlow -> simpleFlowPuzzle()
    WorkshopStage.CollectLatest -> collectLatestPuzzle()
}
