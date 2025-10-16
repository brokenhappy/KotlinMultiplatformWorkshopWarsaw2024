package kmpworkshop.client

import kmpworkshop.common.ApiKey
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.asServer
import kmpworkshop.common.clientApiKey

suspend fun <T> checkCoroutinePuzzle(
    puzzleId: String,
    solution: suspend (T) -> Unit,
    builder: context(CoroutinePuzzleSolutionScope) () -> T,
) {
    when (val result = workshopService.asServer(ApiKey(clientApiKey!!)).doCoroutinePuzzleSolveAttempt(puzzleId) {
        solution(builder())
    }) {
        is CoroutinePuzzleSolutionResult.Failure -> error(result.description)
        is CoroutinePuzzleSolutionResult.Success -> println("Yay, you did it!")
    }
}