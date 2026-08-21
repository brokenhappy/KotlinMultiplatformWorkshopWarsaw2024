package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzleProtocol
import kmpworkshop.common.DefaultApis
import kmpworkshop.common.DefaultApis.callIsDone
import kmpworkshop.common.DefaultApis.callLifetime
import kmpworkshop.common.DefaultApis.getNumber
import kmpworkshop.common.DefaultApis.legacyCancellationCompletion
import kmpworkshop.common.DefaultApis.queryExceptionThrown
import kmpworkshop.common.DefaultApis.submitNumber
import kmpworkshop.common.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch


fun simpleSumPuzzle(): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    val number1 = (0..100).random()
    getNumber.expectCall(number1)
    val number2 = (0..100).random()
    getNumber.expectCall(number2)
    val actual = submitNumber.expectCall(Unit)
    verify(actual == number1 + number2) {
        CoroutinePuzzleErrorMessages.incorrectSum(listOf(number1, number2), actual)
    }
}

fun concurrentSumPuzzle(): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    val randomNumbers = List(2) { (0..100).random() }
    awaitQuiescenceAndVerifyUnmatchedSubmissions(List(randomNumbers.size) { getNumber }) {
        CoroutinePuzzleErrorMessages.sumCallsMustBeConcurrent()
    }
    coroutineScope { randomNumbers.forEach { number -> launch { getNumber.expectCall(number) } } }
    val sum = randomNumbers.sum()

    val actual = submitNumber.expectCall(Unit)
    verify(actual == sum) { CoroutinePuzzleErrorMessages.incorrectSum(randomNumbers, actual) }
}

fun cancellationSumPuzzle(): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    val endLifetime = CompletableDeferred<Unit>()
    launch { callLifetime.expectCall { endLifetime.await() } }
    awaitQuiescenceAndVerifyUnmatchedSubmissions(getNumber, getNumber) {
        CoroutinePuzzleErrorMessages.sumCallsMustBeConcurrent()
    }
    val cancellations = List(2) {
        launch { getNumber.expectCanceledCall { expectCancellation() } }
    }
    launch { callIsDone.expectCall(Unit) }

    // Both getNumber expectations are now installed and waiting, so ending the lifetime cannot race them.
    awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList())
    endLifetime.complete(Unit)
    awaitQuiescenceAndGetUnmatchedSubmissions()

    verify(cancellations.all { it.isCompleted }) {
        CoroutinePuzzleErrorMessages.sumCancellationMustCancelBothCalls()
    }
}

fun exceptionSumPuzzle(waitForCancellationBeforeExceptionEscapes: Boolean): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    val exceptionMessage = "getNumber() could not provide a number"

    launch { callIsDone.expectCall(Unit) }
    val cancellation = launch { getNumber.expectCanceledCall { expectCancellation() } }
    awaitQuiescenceAndVerifyUnmatchedSubmissions(callLifetime, getNumber)
    launch { callLifetime.expectCall(Unit) }
    getNumber.expectThrowingCall(exceptionMessage)

    if (waitForCancellationBeforeExceptionEscapes) {
        awaitQuiescenceAndVerifyUnmatchedSubmissions(legacyCancellationCompletion) {
            CoroutinePuzzleErrorMessages.sumCancellationMustFinishBeforeExceptionEscapes()
        }
        legacyCancellationCompletion.expectCall(Unit)
    } else {
        // Cancellation is the lesson here; its cleanup marker may arrive in a later protocol batch.
        launch { legacyCancellationCompletion.expectCall(Unit) }
        launch { queryExceptionThrown.expectCall(Unit) }
        cancellation.join()
        verify(!cancellation.isCancelled) {
            CoroutinePuzzleErrorMessages.sumExceptionMustCancelOtherCall()
        }
    }
    if (waitForCancellationBeforeExceptionEscapes) queryExceptionThrown.expectCall(Unit)
}
