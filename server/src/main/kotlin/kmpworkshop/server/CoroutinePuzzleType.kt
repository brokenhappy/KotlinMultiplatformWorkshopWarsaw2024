package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzleBatch
import kmpworkshop.common.DefaultApis
import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.CoroutinePuzzleExpectationBatchOrCompletion.Completion
import kmpworkshop.common.CoroutinePuzzleProtocol
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.Resource
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.CollectLatest
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.ChatMessagesBuffered
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.ChatMessagesRendezvous
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.ChatTypingStatusDropOldest
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.ExceptionCatchingWithCoroutines
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.FastFindMaximumAgeCoroutines
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.FileExposureStepOne
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.FileExposureStepThree
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.FileExposureStepTwo
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.FindMaximumAgeCoroutines
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.MappingFromLegacyApisStepFour
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.MappingFromLegacyApisStepOne
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.MappingFromLegacyApisStepThree
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.MappingFromLegacyApisStepTwo
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.SimpleFlow
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.ShipmentTrackingIndependentViews
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.ShipmentTrackingLateEtaCard
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.ShipmentTrackingLazyConnection
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.ShipmentTrackingSharedConnection
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.ShipmentTrackingWhileSubscribed
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.SumOfTwoIntsFast
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.SumOfTwoIntsCancellation
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.SumOfTwoIntsException
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.SumOfTwoIntsExceptionAfterCancellation
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.SumOfTwoIntsSlow
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
        ChatMessagesRendezvous,
        ChatMessagesBuffered,
        ChatTypingStatusDropOldest -> error("Chat channel puzzle evaluators are intentionally not implemented yet")
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
