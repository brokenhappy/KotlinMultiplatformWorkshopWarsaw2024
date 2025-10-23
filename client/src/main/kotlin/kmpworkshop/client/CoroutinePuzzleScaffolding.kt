package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.WorkshopServer
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CoroutineScope

suspend fun <T> checkCoroutinePuzzle(
    workshopServer: WorkshopServer,
    puzzleId: String,
    solution: suspend (T) -> Unit,
    builder: context(CoroutinePuzzleSolutionScope) () -> T,
): CoroutinePuzzleSolutionResult = checkCoroutinePuzzleInternal(workshopServer, puzzleId) { solution(builder()) }

suspend fun checkCoroutinePuzzleInternal(
    workshopServer: WorkshopServer,
    puzzleId: String,
    solution: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit,
): CoroutinePuzzleSolutionResult = withImportantCleanup {
    workshopServer.doCoroutinePuzzleSolveAttempt(puzzleId) {
        solution()
    }
}