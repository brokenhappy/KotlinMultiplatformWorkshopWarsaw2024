package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.CoroutinePuzzleResultWithHistory
import kmpworkshop.common.NumberFlowAndSubmit
import kmpworkshop.common.emitNumber
import kmpworkshop.common.numberFlowAndSubmit
import kmpworkshop.common.solve
import kmpworkshop.common.submitNumber
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun collectLatestPuzzle() = coroutinePuzzle {
    val numbers = (0..< 5).map { (0..100).random() }
    emitNumber.expectCall(numbers.first())
    numbers.zipWithNext().forEach { (_, next) ->
        coroutineScope {
            val readyToGetCanceledHook = CompletableDeferred<Unit>()
            launch {
                emitNumber.expectCall {
                    // Wait until next submit has started,
                    // so we can cancel it with the next emission
                    readyToGetCanceledHook.await()
                    next
                }
            }
            submitNumber.expectCanceledCall {
                readyToGetCanceledHook.complete(Unit) // Let's get this canceled!
                awaitCancellation()
            }
        }
    }
    launch {
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
