package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzleProtocol
import kmpworkshop.common.DefaultApis.getNumber1
import kmpworkshop.common.DefaultApis.getNumber2
import kmpworkshop.common.DefaultApis.runSumSolution
import kmpworkshop.common.DefaultApis.submitNumber
import kmpworkshop.common.ExceptionAcrossRpc
import kmpworkshop.common.Resource
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun simpleSumPuzzle(): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    runSumSolution.expectCall { _, runSolution ->
        repeat(2) {
            coroutineScope {
                launch { runSolution(Unit) }
                val number1 = (0..100).random()
                getNumber1.expectCall(number1)
                val number2 = (0..100).random()
                getNumber2.expectCall(number2)
                val actual = submitNumber.expectCall(Unit)
                verify(actual == number1 + number2) {
                    CoroutinePuzzleErrorMessages.incorrectSum(listOf(number1, number2), actual)
                }
            }
        }
    }
}

fun concurrentSumPuzzle(): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    runSumSolution.expectCall { _, runSolution ->
        repeat(2) {
            coroutineScope {
                launch { runSolution(Unit) }
                val number1 = (0..100).random()
                val number2 = (0..100).random()
                awaitQuiescenceAndVerifyUnmatchedSubmissions(getNumber1, getNumber2) {
                    CoroutinePuzzleErrorMessages.sumCallsMustBeConcurrent()
                }
                coroutineScope {
                    launch { getNumber1.expectCall(number1) }
                    launch { getNumber2.expectCall(number2) }
                }

                val actual = submitNumber.expectCall(Unit)
                verify(actual == number1 + number2) {
                    CoroutinePuzzleErrorMessages.incorrectSum(listOf(number1, number2), actual)
                }
            }
        }
    }
}

fun cancellationSumPuzzle(): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    runSumSolution.expectCall { _, runSolution ->
        repeat(2) {
            coroutineScope {
                val sumSolution = launch { runSolution(Unit) }
                awaitQuiescenceAndVerifyUnmatchedSubmissions(getNumber1, getNumber2) {
                    CoroutinePuzzleErrorMessages.sumCallsMustBeConcurrent()
                }
                val cancellations = listOf(
                    launch { getNumber1.expectCanceledCall { expectCancellation() } },
                    launch { getNumber2.expectCanceledCall { expectCancellation() } },
                )
                awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList())
                sumSolution.cancelAndJoin()
                // The enclosing higher-order call is idle while its cancelled callback unwinds.
                awaitQuiescenceAndVerifyUnmatchedSubmissions(runSumSolution)
                verify(cancellations.all { it.isCompleted }) {
                    CoroutinePuzzleErrorMessages.sumCancellationMustCancelBothCalls()
                }
            }
        }
    }
}

fun exceptionSumPuzzle(): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    runSumSolution.expectCall { _, runSolution ->
        repeat(2) { invocation ->
            coroutineScope {
                val submittedSolution = async { runCatching { runSolution(Unit) } }
                val (throwingNumber, cancelledNumber, exceptionMessage) = if (invocation == 0) {
                    Triple(getNumber1, getNumber2, "sum-$invocation-getNumber1-${(0..Int.MAX_VALUE).random()}")
                } else {
                    Triple(getNumber2, getNumber1, "sum-$invocation-getNumber2-${(0..Int.MAX_VALUE).random()}")
                }

                val cancellation = launch {
                    cancelledNumber.expectCanceledCall { expectCancellation() }
                }
                awaitQuiescenceAndVerifyUnmatchedSubmissions(throwingNumber)
                try {
                    throwingNumber.expectCall { throw ExceptionAcrossRpc(exceptionMessage) }
                } catch (failure: ExceptionAcrossRpc) {
                    check(failure.message == exceptionMessage)
                }
                val result = submittedSolution.await()
                val escapedFailure = result.exceptionOrNull() as? ExceptionAcrossRpc
                verify(escapedFailure?.message == exceptionMessage) {
                    CoroutinePuzzleErrorMessages.sumExceptionMustEscape()
                }
                // The enclosing higher-order call is idle while its failed callback unwinds.
                awaitQuiescenceAndVerifyUnmatchedSubmissions(runSumSolution)
                verify(cancellation.isCompleted) {
                    CoroutinePuzzleErrorMessages.sumExceptionMustCancelOtherCall()
                }
            }
        }
    }
}
