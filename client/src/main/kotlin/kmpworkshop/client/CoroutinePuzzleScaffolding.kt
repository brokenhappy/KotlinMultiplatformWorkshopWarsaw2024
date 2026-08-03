package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleEndPointDescriptor
import kmpworkshop.common.CoroutinePuzzleResultWithHistory
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.callLifetime

/**
 * Endpoints whose calls are internal scaffolding, not something the user themselves called - showing them in the
 * history of a failed solve attempt would only confuse the user, since they never called it directly.
 */
private val endpointsHiddenFromHistory: Set<CoroutinePuzzleEndPointDescriptor> = setOf(callLifetime.descriptor)

fun CoroutinePuzzleEndPointDescriptor.isHiddenFromHistory(): Boolean = this in endpointsHiddenFromHistory

fun CoroutinePuzzleResultWithHistory.toMessage(): String = """
    |
    |${renderCoroutinePuzzleHistory(history)}
    |${result.toMessage()}
""".trimMargin()

fun CoroutinePuzzleSolutionResult.toMessage(): String = when (this) {
    is CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure ->
        "You tried to call " + formatCallAttemptsWithMargins(submissions.map { it.description }) + ".\n" +
            "But you were expected to call exactly " +
            formatCallAttemptsWithMargins(expectations.map { it.description }) + "."
    is CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure ->
        "You made too few function calls. We're still expecting " +
            formatExpectedAlternatives(expectedFollowups.map { it.description }.distinct()) + "."
    is CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure ->
        "You made too many function calls. No more calls were expected right now, but you called " +
            formatCallAttemptsWithMargins(overshotSubmissions.map { it.description }) + "."
    is CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure -> {
        val expectedDescriptions = expectations.map { it.description }.distinct()
        val actionOrActions = if (expectedDescriptions.size == 1) "action is" else "actions are"
        "Currently the expected $actionOrActions " + formatExpectedAlternatives(expectedDescriptions) + ".\n" +
            "But instead you called " +
            formatCallAttemptsWithMargins(unexpectedSubmissions.map { it.description }.distinct()) + "."
    }
    is CoroutinePuzzleSolutionResult.CustomFailure -> message
    CoroutinePuzzleSolutionResult.FullyQuiescent -> "All coroutines got stuck waiting for each other."
    CoroutinePuzzleSolutionResult.Success -> "The puzzle was solved"
}

/** Describes a set of calls that happened (or were expected to happen) together, at the same time. */
private fun formatCallAttemptsWithMargins(attempts: List<String>): String = when (attempts.size) {
    0 -> "nothing"
    1 -> attempts.single()
    else -> "all of these at the same time:\n" + attempts.joinToString("\n") { "  - $it" }
}

/** Describes a set of calls where any single one of them would have been an acceptable next step. */
private fun formatExpectedAlternatives(alternatives: List<String>): String = when (alternatives.size) {
    0 -> "nothing"
    1 -> alternatives.single()
    else -> "one of these:\n" + alternatives.joinToString("\n") { "  - $it" }
}