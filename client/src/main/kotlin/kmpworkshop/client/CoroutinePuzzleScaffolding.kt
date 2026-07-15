package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleEndPointDescriptor
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.WorkshopServer
import kmpworkshop.common.callLifetime
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

/**
 * Endpoints whose calls are internal scaffolding, not something the user themselves called - showing them in the
 * history of a failed solve attempt would only confuse the user, since they never called it directly.
 */
private val endpointsHiddenFromHistory: Set<CoroutinePuzzleEndPointDescriptor> = setOf(callLifetime.descriptor)

fun CoroutinePuzzleEndPointDescriptor.isHiddenFromHistory(): Boolean = this in endpointsHiddenFromHistory

fun CoroutinePuzzleSolutionResult.Failure.toMessage(): String {
    val visibleHistory = history.filterNot { it.isHiddenFromHistory() }
    val historySection = if (visibleHistory.isEmpty()) {
        "  (no actions were called yet)"
    } else {
        visibleHistory.joinToString("\n") { "  - ${it.description}" }
    }
    return "${reason.toMessage()}\n\nThe history of actions was:\n$historySection"
}

fun CoroutinePuzzleSolutionResult.Failure.Reason.toMessage(): String = when (this) {
    is CoroutinePuzzleSolutionResult.Failure.Reason.ExactParallelismMismatch ->
        "You tried to call " + formatCallAttemptsWithMargins(submissions.map { it.description }.distinct()) + ".\n" +
            "But you were expected to call exactly " +
            formatCallAttemptsWithMargins(expectations.map { it.description }.distinct()) + "."
    is CoroutinePuzzleSolutionResult.Failure.Reason.MoreExpectationsThanSubmissions ->
        "You made too few function calls. We're still expecting " +
            formatExpectedAlternatives(expectedFollowups.map { it.description }.distinct()) + "."
    is CoroutinePuzzleSolutionResult.Failure.Reason.MoreSubmissionsThanExpectations ->
        "You made too many function calls. No more calls were expected right now, but you called " +
            formatCallAttemptsWithMargins(overshotSubmissions.map { it.description }.distinct()) + "."
    is CoroutinePuzzleSolutionResult.Failure.Reason.UnexpectedSubmissions -> {
        val expectedDescriptions = expectations.map { it.description }.distinct()
        val actionOrActions = if (expectedDescriptions.size == 1) "action is" else "actions are"
        "Currently the expected $actionOrActions " + formatExpectedAlternatives(expectedDescriptions) + ".\n" +
            "But instead you called " +
            formatCallAttemptsWithMargins(unexpectedSubmissions.map { it.description }.distinct()) + "."
    }
    is CoroutinePuzzleSolutionResult.Failure.Reason.Custom -> message
}

/** Describes a set of calls that happened (or were expected to happen) together, at the same time. */
private fun formatCallAttemptsWithMargins(attempts: List<String>): String = when (attempts.size) {
    0 -> "nothing"
    1 -> attempts.single()
    else -> "all of these, at the same time:\n" + attempts.joinToString("\n") { "  - $it" }
}

/** Describes a set of calls where any single one of them would have been an acceptable next step. */
private fun formatExpectedAlternatives(alternatives: List<String>): String = when (alternatives.size) {
    0 -> "nothing"
    1 -> alternatives.single()
    else -> "one of these:\n" + alternatives.joinToString("\n") { "  - $it" }
}