package kmpworkshop.client

import kmpworkshop.client.CoroutinePuzzleFlowErrorMessages.requestedValuesAfterCompletion
import kmpworkshop.client.CoroutinePuzzleFlowErrorMessages.stoppedBeforeAllValues
import kmpworkshop.client.CoroutinePuzzleFlowErrorMessages.tooFewCollectors
import kmpworkshop.client.CoroutinePuzzleFlowErrorMessages.tooManyCollectors
import kmpworkshop.common.CoroutinePuzzleEndPointDescriptor
import kmpworkshop.common.CoroutinePuzzleExpectationPayload
import kmpworkshop.common.CoroutinePuzzleHistoryBatch
import kmpworkshop.common.CoroutinePuzzleResultWithHistory
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.ValueOrCompletion
import kmpworkshop.common.WithCallId
import kmpworkshop.common.callLifetime
import kmpworkshop.common.emitFileToExpose
import kmpworkshop.common.emitNetworkStrength
import kmpworkshop.common.emitNumber
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Endpoints whose calls are internal scaffolding, not something the user themselves called - showing them in the
 * history of a failed solve attempt would only confuse the user, since they never called it directly.
 */
private val endpointsHiddenFromHistory: Set<CoroutinePuzzleEndPointDescriptor> = setOf(callLifetime.descriptor)

fun CoroutinePuzzleEndPointDescriptor.isHiddenFromHistory(): Boolean = this in endpointsHiddenFromHistory

fun CoroutinePuzzleResultWithHistory.toMessage(): String = """
    |
    |${renderCoroutinePuzzleHistory(history)}
    |${flowCollectorMismatchMessage() ?: result.toMessage()}
""".trimMargin()

private val flowEndpointNames = mapOf(
    emitNumber.descriptor to "the numbers Flow",
    emitFileToExpose.descriptor to "the currentFileToExpose Flow",
    emitNetworkStrength.descriptor to "the network-strength Flow",
)

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

private fun CoroutinePuzzleResultWithHistory.flowCollectorMismatchMessage(): String? {
    val mismatchedEndpoints = when (val failure = result) {
        is CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure -> failure.expectedFollowups
        is CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure -> failure.overshotSubmissions
        else -> return null
    }
    val endpoint = mismatchedEndpoints.distinct().singleOrNull() ?: return null
    val flowName = flowEndpointNames[endpoint] ?: return null
    val calls = history.filterIsInstance<CoroutinePuzzleHistoryBatch.Submission>()
        .flatMap { it.entries }
        .mapNotNull { entry ->
            val call = entry.payload as? CoroutinePuzzleSubmissionPayload.CallSubmitted ?: return@mapNotNull null
            if (call.endPoint != endpoint) return@mapNotNull null
            entry.callId to Json.decodeFromJsonElement<WithCallId<Unit>>(call.arg).callId
        }

    return when (result) {
        is CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure ->
            if (calls.map { it.second }.distinct().size > 1) tooManyCollectors(flowName)
            else requestedValuesAfterCompletion(flowName)
        is CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure ->
            if (calls.lastOrNull()?.first?.wasCompletedIn(history) == true) tooFewCollectors(flowName)
            else stoppedBeforeAllValues(flowName)
        else -> null
    }
}

private fun Long.wasCompletedIn(history: List<CoroutinePuzzleHistoryBatch>): Boolean {
    val result = history.filterIsInstance<CoroutinePuzzleHistoryBatch.Expectation>()
        .flatMap { it.entries }
        .firstOrNull { it.callId == this }
        ?.payload as? CoroutinePuzzleExpectationPayload.CallAnswered
        ?: return false
    return Json.decodeFromJsonElement<WithCallId<ValueOrCompletion<JsonElement>>>(result.result).payload ===
        ValueOrCompletion.Completion
}

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
            "But instead you were doing " +
            formatCallAttemptsWithMargins(
                unexpectedSubmissions.map { it.description }.distinct(),
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
