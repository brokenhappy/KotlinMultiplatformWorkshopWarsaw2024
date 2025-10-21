package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.GetNumberAndSubmit
import kmpworkshop.common.cancelSubmit
import kmpworkshop.common.getNumber
import kmpworkshop.common.getNumberAndSubmit
import kmpworkshop.common.importantCleanup
import kmpworkshop.common.solve
import kmpworkshop.common.submitCall
import kmpworkshop.common.submitNumber
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue


fun simpleSumPuzzle() = coroutinePuzzle {
    val number1 = (0..100).random()
    getNumber.expectCall(number1)
    val number2 = (0..100).random()
    getNumber.expectCall(number2)
    val actual = submitNumber.expectCall(Unit)
    verify(actual == number1 + number2) {
        "The value that you submit must the sum of the numbers you got ($number1 + $number2), but got $actual"
    }
}

fun timedSumPuzzle() = coroutinePuzzle {
    val sum = withTimeoutOrNull(1.8.seconds) {
        puzzleScope {
            val number1 = (0..100).random()
            launchBranch {
                getNumber.expectCall {
                    delay(1.seconds)
                    number1
                }
            }
            val number2 = (0..100).random()
            getNumber.expectCall {
                delay(1.seconds)
                number2
            }

            number1 + number2
        }
    } ?: fail("Too slow! Expected to take less than 1.8 seconds")

    val actual = submitNumber.expectCall(Unit)
    verify(actual == sum) { "The value that you submit must the sum of the numbers you got ($sum), but got $actual" }
}

suspend fun doSimpleSumPuzzle(
    onUse: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
): CoroutinePuzzleSolutionResult = doSumPuzzle(simpleSumPuzzle(), onUse)

suspend fun doTimedSumPuzzle(
    onUse: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
): CoroutinePuzzleSolutionResult = doSumPuzzle(timedSumPuzzle(), onUse)

private suspend fun doSumPuzzle(
    puzzle: CoroutinePuzzle,
    onUse: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
): CoroutinePuzzleSolutionResult = puzzle.solve {
    withImportantCleanup {
        onUse(getNumberAndSubmit())
    }
}
