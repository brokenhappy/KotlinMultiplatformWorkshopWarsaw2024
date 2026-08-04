package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzleProtocol
import kmpworkshop.common.Resource
import kmpworkshop.common.getNumber
import kmpworkshop.common.submitNumber
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

fun timedSumPuzzle(): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    val randomNumbers = List(2) { (0..100).random() }
    awaitQuiescenceAndVerifyUnmatchedSubmissions(List(randomNumbers.size) { getNumber }) {
        CoroutinePuzzleErrorMessages.sumCallsMustBeConcurrent()
    }
    coroutineScope { randomNumbers.forEach { number -> launch { getNumber.expectCall(number) } } }
    val sum = randomNumbers.sum()

    val actual = submitNumber.expectCall(Unit)
    verify(actual == sum) { CoroutinePuzzleErrorMessages.incorrectSum(randomNumbers, actual) }
}
