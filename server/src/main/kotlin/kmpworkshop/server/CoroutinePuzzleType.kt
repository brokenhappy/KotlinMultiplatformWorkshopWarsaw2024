package kmpworkshop.server

import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleExpectationBatchOrCompletion.Completion
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.*
import kmpworkshop.common.CoroutinePuzzleExpectationBatchOrCompletion as BatchOrCompletion

object CoroutinePuzzleType : PuzzleType<CoroutinePuzzleStage, BatchOrCompletion, CoroutinePuzzleBatch<CoroutinePuzzleSubmissionPayload>> {
    override fun enumEntries(): List<CoroutinePuzzleStage> = kotlin.enums.enumEntries()
    override fun customError(message: String): BatchOrCompletion =
        Completion(CoroutinePuzzleSolutionResult.CustomFailure(message))

    override fun isSuccessfulCompletion(outgoing: BatchOrCompletion): Boolean =
        outgoing is Completion && outgoing.result is CoroutinePuzzleSolutionResult.Success

    override fun findPuzzleFor(stage: CoroutinePuzzleStage): Resource<CoroutinePuzzleProtocol> = when (stage) {
        SumOfTwoIntsSlow -> simpleSumPuzzle()
        SumOfTwoIntsFast -> concurrentSumPuzzle()
        SumOfTwoIntsCancellation -> cancellationSumPuzzle()
        SumOfTwoIntsException -> exceptionSumPuzzle(waitForCancellationBeforeExceptionEscapes = false)
        SumOfTwoIntsExceptionAfterCancellation -> exceptionSumPuzzle(waitForCancellationBeforeExceptionEscapes = true)
        FindMaximumAgeCoroutines -> maximumAgeFindingTheSecondCoroutinePuzzle(mustBeConcurrent = false)
        FastFindMaximumAgeCoroutines -> maximumAgeFindingTheSecondCoroutinePuzzle(mustBeConcurrent = true)
        MappingFromLegacyApisStepOne -> mappingLegacyApiHappyPathCoroutinePuzzle()
        MappingFromLegacyApisStepTwo -> mappingLegacyApiCoroutinePuzzleWithException()
        MappingFromLegacyApisStepThree -> mappingLegacyApiCoroutinePuzzleWithEscapingCancellation()
        MappingFromLegacyApisStepFour -> mappingLegacyApiCoroutinePuzzleStepFour()
        ExceptionCatchingWithCoroutines -> coroutineExceptionHandlingCoroutinePuzzle()
        SimpleFlow -> simpleFlowPuzzle()
        CollectLatest -> collectLatestPuzzle()
        ChatMessagesRendezvous -> chatMessagesRendezvousPuzzle()
        ChatMessagesBuffered -> chatMessagesBufferedPuzzle()
        ChatTypingStatusDropOldest -> chatTypingStatusDropOldestPuzzle()
        ShipmentTrackingIndependentViews -> shipmentTrackingIndependentViewsPuzzle()
        ShipmentTrackingSharedConnection -> shipmentTrackingSharedConnectionPuzzle()
        ShipmentTrackingLateEtaCard -> shipmentTrackingLateEtaCardPuzzle()
        ShipmentTrackingLazyConnection -> shipmentTrackingLazyConnectionPuzzle()
        ShipmentTrackingWhileSubscribed -> shipmentTrackingWhileSubscribedPuzzle()
        FileExposureStepOne -> fileExposureStepOnePuzzle()
        FileExposureStepTwo -> fileExposureStepTwoPuzzle()
        FileExposureStepThree -> fileExposureStepThreePuzzle()
    }
}
