package kmpworkshop.client

import kmpworkshop.client.CoroutinePuzzleFlowErrorMessages.requestedValuesAfterCompletion
import kmpworkshop.client.CoroutinePuzzleFlowErrorMessages.stoppedBeforeAllValues
import kmpworkshop.client.CoroutinePuzzleFlowErrorMessages.tooFewCollectors
import kmpworkshop.client.CoroutinePuzzleFlowErrorMessages.tooManyCollectors
import kmpworkshop.common.CoroutinePuzzleEndPointId
import kmpworkshop.common.CoroutinePuzzleHistoryBatch
import kmpworkshop.common.CoroutinePuzzleResultWithHistory
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.WithCallId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Endpoints whose calls are internal scaffolding, not something the user themselves called - showing them in the
 * history of a failed solve attempt would only confuse the user, since they never called it directly.
 */
context(clientMetadata: ClientMetadata)
fun CoroutinePuzzleEndPointId.isHiddenFromHistory(): Boolean = clientMetadata.isHiddenInHistory(this)

context(clientMetadata: ClientMetadata)
fun CoroutinePuzzleResultWithHistory.toMessage(): String = """
    |
    |${renderCoroutinePuzzleHistory(history)}
    |${flowCollectorMismatchMessage() ?: result.toMessage()}
""".trimMargin()

context(clientMetadata: ClientMetadata)
fun CoroutinePuzzleResultWithHistory.toMessageWithMetadata(): String = toMessage()

object CoroutinePuzzleFlowErrorMessages {
    fun tooManyCollectors(flowName: String): String =
        "Your solution started too many collectors of $flowName. No additional collector was expected."

    fun tooFewCollectors(flowName: String): String =
        "Your solution started too few collectors of $flowName. Another collector was expected."

    fun requestedValuesAfterCompletion(flowName: String): String =
        "A collector of $flowName requested more values after that Flow had completed."

    fun stoppedBeforeAllValues(flowName: String): String =
        "A collector of $flowName stopped requesting values before all expected values were emitted."
}

context(clientMetadata: ClientMetadata)
private fun CoroutinePuzzleResultWithHistory.flowCollectorMismatchMessage(): String? {
    val mismatchedEndpoints = when (val failure = result) {
        is CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure -> failure.expectedFollowups.map { it.endPoint }
        is CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure -> failure.overshotSubmissions
        else -> return null
    }
    val endpoint = mismatchedEndpoints.distinct().singleOrNull() ?: return null
    if (!clientMetadata.isFlowEndpoint(endpoint)) return null
    val flowName = clientMetadata.descriptionFor(endpoint)
    val calls = history.filterIsInstance<CoroutinePuzzleHistoryBatch.Submission>()
        .flatMap { it.entries }
        .mapNotNull { entry ->
            val call = entry.payload as? CoroutinePuzzleSubmissionPayload.CallSubmitted ?: return@mapNotNull null
            if (call.endPoint != endpoint) return@mapNotNull null
            entry.callId to Json.decodeFromJsonElement<WithCallId<Unit>>(call.arg).callId
        }

    return when (val failure = result) {
        is CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure ->
            if (calls.map { it.second }.distinct().size > 1) tooManyCollectors(flowName)
            else requestedValuesAfterCompletion(flowName)
        is CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure ->
            if (failure.expectedFollowups.any { it.expectedArgument != null }) stoppedBeforeAllValues(flowName)
            else tooFewCollectors(flowName)
        else -> null
    }
}

context(clientMetadata: ClientMetadata)
fun CoroutinePuzzleSolutionResult.toMessage(): String = when (this) {
    is CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure ->
        "You tried to call " + formatCallAttemptsWithMargins(submissions.map(clientMetadata::descriptionFor)) + ".\n" +
        "But you were expected to call exactly " +
            formatCallAttemptsWithMargins(expectations.map { clientMetadata.descriptionFor(it.endPoint) }) + "."
    is CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure ->
        "You made too few function calls. We're still expecting " +
            formatExpectedAlternatives(expectedFollowups.map { clientMetadata.descriptionFor(it.endPoint) }.distinct()) + "."
    is CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure ->
        "You made too many function calls. No more calls were expected right now, but you called " +
            formatCallAttemptsWithMargins(overshotSubmissions.map(clientMetadata::descriptionFor)) + "."
    is CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure -> {
        val expectedDescriptions = expectations.map { clientMetadata.descriptionFor(it.endPoint) }.distinct()
        val actionOrActions = if (expectedDescriptions.size == 1) "action is" else "actions are"
        "Currently the expected $actionOrActions " + formatExpectedAlternatives(expectedDescriptions) + ".\n" +
            "But instead you were doing " +
            formatCallAttemptsWithMargins(
                unexpectedSubmissions.map(clientMetadata::descriptionFor).distinct(),
                concurrentActions = true,
            ) + "."
    }
    is CoroutinePuzzleSolutionResult.CustomFailure -> message
    CoroutinePuzzleSolutionResult.FullyQuiescent -> "All coroutines got stuck waiting for each other."
    CoroutinePuzzleSolutionResult.Success -> "The puzzle was solved"
}

/** Describes a set of calls that happened (or were expected to happen) together, at the same time. */
private fun formatCallAttemptsWithMargins(
    attempts: List<String>,
    concurrentActions: Boolean = false,
): String = when (attempts.size) {
    0 -> "nothing"
    1 -> attempts.single()
    else -> "all of these${if (concurrentActions) " actions" else ""} at the same time:\n" +
        attempts.joinToString("\n") { "  - $it" }
}

/** Describes a set of calls where any single one of them would have been an acceptable next step. */
private fun formatExpectedAlternatives(alternatives: List<String>): String = when (alternatives.size) {
    0 -> "nothing"
    1 -> alternatives.single()
    else -> "one of these:\n" + alternatives.joinToString("\n") { "  - $it" }
}
