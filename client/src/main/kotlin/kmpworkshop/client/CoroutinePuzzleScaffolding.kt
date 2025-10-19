package kmpworkshop.client

import kmpworkshop.common.ApiKey
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.asServer
import kmpworkshop.common.clientApiKey
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CoroutineScope

suspend fun <T> checkCoroutinePuzzle(
    puzzleId: String,
    solution: suspend (T) -> Unit,
    builder: context(CoroutinePuzzleSolutionScope) () -> T,
) {
    checkCoroutinePuzzleInternal(puzzleId, { solution(it) }, builder)
}

suspend fun <T> checkCoroutinePuzzleInternal(
    puzzleId: String,
    solution: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.(T) -> Unit,
    builder: context(CoroutinePuzzleSolutionScope) () -> T,
) {
    withImportantCleanup {
        when (val result = workshopService.asServer(ApiKey(clientApiKey!!)).doCoroutinePuzzleSolveAttempt(puzzleId) {
            solution(builder())
        }) {
            is CoroutinePuzzleSolutionResult.Failure -> error(result.description)
            is CoroutinePuzzleSolutionResult.Success -> println("Yay, you did it!")
        }
    }
}