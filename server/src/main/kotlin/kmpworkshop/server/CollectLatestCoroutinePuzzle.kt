package kmpworkshop.server

import kmpworkshop.common.emitNumber
import kmpworkshop.common.submitNumber
import kotlinx.coroutines.CompletableDeferred
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
    val actual = submitNumber.expectCall(Unit)
    verify(actual == numbers.last()) {
        CoroutinePuzzleErrorMessages.wrongFlowValue(actual, numbers.last())
    }
}

fun simpleFlowPuzzle() = coroutinePuzzle {
    repeat(3) {
        val number = (0..100).random()
        emitNumber.expectCall(number) // Emit into flow
        val actual = submitNumber.expectCall(Unit)
        verify(actual == number) {
            CoroutinePuzzleErrorMessages.wrongFlowValue(actual, number)
        }
    }
    emitNumber.expectCall(null) // Close flow
}
