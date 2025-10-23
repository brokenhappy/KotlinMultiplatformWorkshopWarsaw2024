package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.NumberFlowAndSubmit
import kmpworkshop.common.emitNumber
import kmpworkshop.common.numberFlowAndSubmit
import kmpworkshop.common.solve
import kmpworkshop.common.submitNumber
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

fun collectLatestPuzzle() = coroutinePuzzle {
    val numbers = (0..< 5).map { (0..100).random() }
    emitNumber.expectCall(numbers.first())
    numbers.zipWithNext().forEach { (last, next) ->
        puzzleScope {
            val readyToGetCanceledHook = CompletableDeferred<Unit>()
            launchBranch {
                emitNumber.expectCall {
                    // Wait until next submit has started,
                    // so we can cancel it with the next emission
                    readyToGetCanceledHook.await()
                    next
                }
            }
            val actual = submitNumber.expectCall {
                readyToGetCanceledHook.complete(Unit) // Let's get this canceled!
                withTimeoutOrNull(2.seconds) {
                    awaitCancellationOfMatchingSubmitCall()
                } ?: fail("submitNumber() is expected to be canceled by the new emission into the flow")
            }
            verify(actual == last) { "The value that you submit must be a value collected from the flow!" }
        }
    }
    launchBranch {
        emitNumber.expectCall(null) // Close the flow
    }
    // Last call should successfully finish
    verify(submitNumber.expectCall(Unit) == numbers.last()) {
        "The value that you submit must be a value collected from the flow!"
    }
}

fun simpleFlowPuzzle() = coroutinePuzzle {
    repeat(3) {
        val number = (0..100).random()
        emitNumber.expectCall(number) // Emit into flow
        verify(submitNumber.expectCall(Unit) == number) {
            "The value that you submit must be a value collected from the flow!"
        }
    }
    emitNumber.expectCall(null) // Close flow
}

suspend fun doCollectLatestPuzzle(
    onUse: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
): CoroutinePuzzleSolutionResult = doFlowAndSubmitPuzzle(collectLatestPuzzle(), onUse)

suspend fun doSimpleCollectPuzzle(
    onUse: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
): CoroutinePuzzleSolutionResult = doFlowAndSubmitPuzzle(simpleFlowPuzzle(), onUse)

private suspend fun doFlowAndSubmitPuzzle(
    puzzle: CoroutinePuzzle,
    onUse: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
): CoroutinePuzzleSolutionResult = puzzle.solve {
    withImportantCleanup {
        onUse(numberFlowAndSubmit())
    }
}
