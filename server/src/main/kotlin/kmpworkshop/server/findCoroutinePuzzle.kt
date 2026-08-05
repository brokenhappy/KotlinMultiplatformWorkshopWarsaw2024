package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzleProtocol
import kmpworkshop.common.Resource
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.*

fun findCoroutinePuzzleFor(stage: CoroutinePuzzleStage): Resource<CoroutinePuzzleProtocol> = when (stage) {
    SumOfTwoIntsSlow -> simpleSumPuzzle()
    SumOfTwoIntsFast -> timedSumPuzzle()
    FindMaximumAgeCoroutines -> maximumAgeFindingTheSecondCoroutinePuzzle(mustBeConcurrent = false)
    FastFindMaximumAgeCoroutines -> maximumAgeFindingTheSecondCoroutinePuzzle(mustBeConcurrent = true)
    MappingFromLegacyApisStepOne -> mappingLegacyApiHappyPathCoroutinePuzzle()
    MappingFromLegacyApisStepTwo -> mappingLegacyApiCoroutinePuzzleWithException()
    MappingFromLegacyApisStepThree -> mappingLegacyApiCoroutinePuzzleWithEscapingCancellation()
    MappingFromLegacyApisStepFour -> mappingLegacyApiCoroutinePuzzleStepFour()
    ExceptionCatchingWithCoroutines -> coroutineExceptionHandlingCoroutinePuzzle()
    SimpleFlow -> simpleFlowPuzzle()
    CollectLatest -> collectLatestPuzzle()
    FileExposureStepOne -> fileExposureStepOnePuzzle()
    FileExposureStepTwo -> fileExposureStepTwoPuzzle()
    FileExposureStepThree -> fileExposureStepThreePuzzle()
}
