package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleExpectationPayload
import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.CoroutinePuzzleEndPointId
import kmpworkshop.common.CoroutinePuzzleHistoryBatch
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.ValueOrCompletion
import kmpworkshop.common.WithCallId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

enum class TimelineCompletion { RETURNED, THREW, CANCELLED }

data class CoroutineTimelineCall(
    val callId: Long,
    val endpoint: CoroutinePuzzleEndPointId,
    val argument: JsonElement,
    val startBatch: Int,
    val cancellationRequestedBatch: Int? = null,
    val endBatch: Int? = null,
    val completion: TimelineCompletion? = null,
    val returnValue: JsonElement? = null,
    val exceptionMessage: String? = null,
    val flowCompleted: Boolean = false,
    val events: List<CoroutineTimelineEvent> = emptyList(),
)

data class CoroutineTimelineEvent(
    val callId: Long,
    val startBatch: Int,
    val cancellationRequestedBatch: Int? = null,
    val endBatch: Int? = null,
    val completion: TimelineCompletion? = null,
    val returnValue: JsonElement? = null,
    val exceptionMessage: String? = null,
    val flowCompleted: Boolean = false,
)

data class CoroutineTimelineExpectedCall(
    val endpoint: CoroutinePuzzleEndPointId,
    val expectedArgument: JsonElement? = null,
    val expectedCancellationOfCallId: Long? = null,
)

context(clientMetadata: ClientMetadata)
fun coroutineTimeline(batches: List<CoroutinePuzzleHistoryBatch>): List<CoroutineTimelineCall> {
    val calls = linkedMapOf<Long, CoroutineTimelineCall>()
    val flowGroups = mutableMapOf<Long, String>()
    batches.forEachIndexed { batchIndex, batch ->
        when (batch) {
            is CoroutinePuzzleHistoryBatch.Submission -> batch.entries.forEach { (id, payload) ->
                when (payload) {
                    is CoroutinePuzzleSubmissionPayload.CallSubmitted -> if (!payload.endPoint.isHiddenFromHistory()) {
                        check(id !in calls) { "Call $id was submitted more than once" }
                        calls[id] = CoroutineTimelineCall(id, payload.endPoint, payload.arg, batchIndex)
                        if (clientMetadata.isFlowEndpoint(payload.endPoint)) {
                            flowGroups[id] = "${payload.endPoint.stringValue}:${Json.decodeFromJsonElement<WithCallId<JsonElement>>(payload.arg).callId}"
                        }
                    }
                    CoroutinePuzzleSubmissionPayload.CallShouldCancel -> calls[id]?.let {
                        calls[id] = it.copy(cancellationRequestedBatch = batchIndex)
                    }
                }
            }
            is CoroutinePuzzleHistoryBatch.Expectation -> batch.entries.forEach { (id, payload) ->
                calls[id]?.let { call ->
                    calls[id] = when (payload) {
                        is CoroutinePuzzleExpectationPayload.CallAnswered -> call.copy(
                            endBatch = batchIndex,
                            completion = TimelineCompletion.RETURNED,
                            returnValue = payload.result.unwrapCollectorValue(),
                            flowCompleted = clientMetadata.isFlowEndpoint(call.endpoint) && payload.result.isCollectorCompletion(),
                        )
                        is CoroutinePuzzleExpectationPayload.CallThrew -> call.copy(
                            endBatch = batchIndex,
                            completion = TimelineCompletion.THREW,
                            exceptionMessage = payload.message,
                        )
                        CoroutinePuzzleExpectationPayload.CallCancellationCompleted -> call.copy(endBatch = batchIndex, completion = TimelineCompletion.CANCELLED)
                    }
                }
            }
        }
    }
    return calls.values.groupBy { flowGroups[it.callId] ?: "call:${it.callId}" }.values.map { group ->
        if (group.size == 1 && !clientMetadata.isFlowEndpoint(group.single().endpoint)) group.single()
        else group.first().copy(events = group.map { call ->
            CoroutineTimelineEvent(
                call.callId,
                call.startBatch,
                call.cancellationRequestedBatch,
                call.endBatch,
                call.completion,
                call.returnValue,
                call.exceptionMessage,
                call.flowCompleted,
            )
        })
    }
}

context(clientMetadata: ClientMetadata)
internal fun expectedTimelineCalls(result: CoroutinePuzzleSolutionResult?): List<CoroutineTimelineExpectedCall> =
    when (result) {
        is CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure -> result.expectedFollowups.map {
            CoroutineTimelineExpectedCall(it.endPoint, it.expectedArgument, it.expectedCancellationOfCallId)
        }
        is CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure -> result.expectations.map {
            CoroutineTimelineExpectedCall(it.endPoint, it.expectedArgument, it.expectedCancellationOfCallId)
        }
        is CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure -> result.expectations.map {
            CoroutineTimelineExpectedCall(it.endPoint, it.expectedArgument, it.expectedCancellationOfCallId)
        }
        else -> emptyList()
    }
        .filterNot { it.endpoint.isHiddenFromHistory() }

internal fun JsonElement.isUnitValue(): Boolean = this is JsonObject && isEmpty()

/** Keep the timeline focused on the value a collector observed, not its transport call id. */
internal fun JsonElement.unwrapCollectorValue(): JsonElement =
    (this as? JsonObject)
        ?.get("payload")
        ?.let { it as? JsonObject }
        ?.get("value")
        ?: this

internal fun JsonElement.isCollectorCompletion(): Boolean =
    runCatching {
        Json.decodeFromJsonElement<WithCallId<ValueOrCompletion<JsonElement>>>(this).payload === ValueOrCompletion.Completion
    }.getOrDefault(false)
