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
    WorkshopStage.FindMaximumAgeCoroutines -> maximumAgeFindingTheSecondCoroutinePuzzle(mustBeConcurrent = false)
    WorkshopStage.FastFindMaximumAgeCoroutines -> maximumAgeFindingTheSecondCoroutinePuzzle(mustBeConcurrent = true)
    WorkshopStage.MappingFromLegacyApisStepOne -> mappingLegacyApiHappyPathCoroutinePuzzle()
    WorkshopStage.MappingFromLegacyApisStepTwo -> mappingLegacyApiCoroutinePuzzleWithException()
    WorkshopStage.MappingFromLegacyApisStepThree -> mappingLegacyApiCoroutinePuzzleWithEscapingCancellation()
    WorkshopStage.MappingFromLegacyApisStepFour -> mappingLegacyApiCoroutinePuzzleStepFour()
    WorkshopStage.SimpleFlow -> simpleFlowPuzzle()
    WorkshopStage.CollectLatest -> collectLatestPuzzle()
}
