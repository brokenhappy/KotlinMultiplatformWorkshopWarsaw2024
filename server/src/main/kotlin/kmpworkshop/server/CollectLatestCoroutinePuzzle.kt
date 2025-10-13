package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.NumberFlowAndSubmit
import kmpworkshop.common.cancelSubmit
import kmpworkshop.common.emitNumber
import kmpworkshop.common.numberFlowAndSubmit
import kmpworkshop.common.solve
import kmpworkshop.common.submitNumber
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import workshop.adminaccess.map
import kotlin.time.Duration.Companion.seconds

fun collectLatestPuzzle() = coroutinePuzzle {
    val numbers = (0..< 5).map { (0..100).random() }
    launchBranch {
        for (number in numbers) {
            emitNumber.expectCall(number) // Emit into flow
        }
        emitNumber.expectCall(null) // Finish the flow
    }
    numbers.dropLast(1).forEach { number ->
        val actual = submitNumber.expectCall {
            withTimeoutOrNull(2.seconds) {
                cancelSubmit.expectCall(Unit)
            } ?: fail("submitNumber() is expected to be canceled by the new emission into the flow")
        }
        verify(actual == number) { "The value that you submit must be a value collected from the flow!" }
    }
    numbers.lastOrNull()?.let { lastNumber ->
        verify(submitNumber.expectCall(Unit) == lastNumber) {
            "The value that you submit must be a value collected from the flow!"
        }
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
