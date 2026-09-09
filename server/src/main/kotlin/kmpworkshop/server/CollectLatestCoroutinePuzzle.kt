package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzleEndPoint
import kmpworkshop.common.DefaultApis.emitNumber
import kmpworkshop.common.DefaultApis.submitNumber
import kmpworkshop.common.UnmatchedSubmission
import kmpworkshop.common.runOnBiggerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun collectLatestPuzzle() = coroutinePuzzle {
    val numbers = (0..< 5).map { (0..100).random() }
    emitNumber.expectingFlowCollector().use { collectors ->
        collectors.use { (_, emit) ->
            coroutineScope {
                launch { emit(numbers.first()) }
                numbers.zipWithNext().forEach { (previous, next) ->
                    // Collection must remain ready for another number while the current submission is running.
                    val submissions = awaitQuiescenceAndGetUnmatchedSubmissions()
                    verifyUnmatchedSubmissions(submissions, listOf(emitNumber, submitNumber)) {
                        if (submissions.map { it.endPoint } == listOf(submitNumber)) {
                            CoroutinePuzzleErrorMessages.flowMustKeepCollectingWhileSubmitting()
                        } else null
                    }
                    val actual = submissions.argumentOf(submitNumber)
                    verify(actual == previous) { CoroutinePuzzleErrorMessages.wrongFlowValue(actual, previous) }
                    coroutineScope transition@ {
                        launch { emit(next) }
                        submitNumber.expectCanceledCall {
                            try {
                                expectCancellation()
                            } finally {
                                // A buffered collector may request another value, but cannot start its work
                                // until cancellation finishes. Keep this check in the tracked evaluator scope.
                                runOnBiggerScope(this@transition) {
                                    val pendingWork = awaitQuiescenceAndGetUnmatchedSubmissions()
                                        .filterNot { it.endPoint == emitNumber }
                                    verifyUnmatchedSubmissions(pendingWork, emptyList()) {
                                        CoroutinePuzzleErrorMessages.flowCancellationMustFinishBeforeNextValue()
                                    }
                                }
                            }
                        }
                    }
                }
                val actual = submitNumber.expectCall(Unit)
                verify(actual == numbers.last()) {
                    CoroutinePuzzleErrorMessages.wrongFlowValue(actual, numbers.last())
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> List<UnmatchedSubmission<*>>.argumentOf(endpoint: CoroutinePuzzleEndPoint<T, *>): T =
    single { it.endPoint == endpoint }.argument as T

fun simpleFlowPuzzle() = coroutinePuzzle {
    emitNumber.expectingFlowCollector().use { collectors ->
        collectors.use { (_, emitNumber) ->
            repeat(3) {
                val number = (0..100).random()
                coroutineScope {
                    launch { emitNumber(number) }
                    val actual = submitNumber.expectCall(Unit)
                    verify(actual == number) {
                        CoroutinePuzzleErrorMessages.wrongFlowValue(actual, number)
                    }
                }
            }
        }
    }
}
