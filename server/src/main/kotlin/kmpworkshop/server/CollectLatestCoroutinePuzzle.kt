package kmpworkshop.server

import kmpworkshop.common.DefaultApis.emitNumber
import kmpworkshop.common.DefaultApis.submitNumber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun collectLatestPuzzle() = coroutinePuzzle {
    val numbers = (0..< 5).map { (0..100).random() }
    emitNumber.expectingFlowCollector().use { collectors ->
        collectors.use { (_, emitNumber) ->
            emitNumber(numbers.first())
            numbers.zipWithNext().forEach { (_, next) ->
                coroutineScope {
                    val readyToGetCanceledHook = CompletableDeferred<Unit>()
                    launch {
                        // Wait until next submit has started,
                        // so we can cancel it with the next emission
                        readyToGetCanceledHook.await()
                        emitNumber.invoke(next)
                    }
                    submitNumber.expectCanceledCall {
                        readyToGetCanceledHook.complete(Unit) // Let's get this canceled!
                        awaitCancellation()
                    }
                }
            }
            // The last collector request and its final submit can run concurrently.
            val actual = submitNumber.expectCall(Unit)
            verify(actual == numbers.last()) {
                CoroutinePuzzleErrorMessages.wrongFlowValue(actual, numbers.last())
            }
        }
    }
}

fun simpleFlowPuzzle() = coroutinePuzzle {
    emitNumber.expectingFlowCollector().use { collectors ->
        collectors.use { (_, emitNumber) ->
            repeat(3) {
                val number = (0..100).random()
                emitNumber(number)
                val actual = submitNumber.expectCall(Unit)
                verify(actual == number) {
                    CoroutinePuzzleErrorMessages.wrongFlowValue(actual, number)
                }
            }
        }
    }
}
