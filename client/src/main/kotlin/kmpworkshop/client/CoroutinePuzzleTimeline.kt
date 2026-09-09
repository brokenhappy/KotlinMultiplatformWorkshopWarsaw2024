package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleCallPayload
import kmpworkshop.common.CoroutinePuzzleCallPayload.Outcome

import kmpworkshop.common.CoroutinePuzzleEndPointId
import kmpworkshop.common.CoroutinePuzzleHistoryBatch
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

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
    val argument: JsonElement? = null,
)

data class CoroutineTimelineExpectedCall(
    val endpoint: CoroutinePuzzleEndPointId,
    val expectedArgument: JsonElement? = null,
    val expectedCancellationOfCallId: Long? = null,
    val expectedCallbackCompletionOfCallId: Long? = null,
    val expectedCancellationCompletionOfCallId: Long? = null,
)

context(clientMetadata: ClientMetadata)
fun coroutineTimeline(batches: List<CoroutinePuzzleHistoryBatch>): List<CoroutineTimelineCall> {
    val calls = linkedMapOf<Long, CoroutineTimelineCall>()
    val parents = mutableMapOf<Long, Long>()
    batches.forEachIndexed { batchIndex, batch ->
        for ((id, payload) in batch.entries) {
            when (payload) {
                is CoroutinePuzzleCallPayload.Submission -> {
                    if (!payload.endPoint.isHiddenFromHistory()) {
                        check(id !in calls) { "Call $id was submitted more than once" }
                        val argument = if (payload is CoroutinePuzzleCallPayload.HigherOrderCallSubmitted) {
                            (payload.arg as JsonObject).getValue("first")
                        } else payload.arg
                        calls[id] = CoroutineTimelineCall(id, payload.endPoint, argument, batchIndex)
                    }
                }
                is CoroutinePuzzleCallPayload.CallbackInvoked -> {
                    val parent = calls[payload.parentCallId] ?: continue
                    parents[id] = parent.callId
                    calls[id] = CoroutineTimelineCall(id, parent.endpoint, payload.arg, batchIndex,
                        returnValue = payload.arg.takeIf { clientMetadata.isFlowEndpoint(parent.endpoint) })
                }
                else -> {
                    val call = calls[id] ?: continue
                    if (call.endBatch != null) continue
                    calls[id] = when (payload) {
                        CoroutinePuzzleCallPayload.Cancel -> call.copy(
                            cancellationRequestedBatch = call.cancellationRequestedBatch ?: batchIndex,
                        )
                        is Outcome.Returned -> call.copy(
                            endBatch = batchIndex,
                            completion = if (call.cancellationRequestedBatch != null) TimelineCompletion.CANCELLED else TimelineCompletion.RETURNED,
                            returnValue = if (id in parents && clientMetadata.isFlowEndpoint(call.endpoint)) call.argument else payload.result as JsonElement,
                            flowCompleted = id !in parents && clientMetadata.isFlowEndpoint(call.endpoint),
                        )
                        is Outcome.Threw -> call.copy(
                            endBatch = batchIndex, completion = TimelineCompletion.THREW, exceptionMessage = payload.message,
                        )
                        Outcome.Cancelled -> call.copy(endBatch = batchIndex, completion = TimelineCompletion.CANCELLED)
                        else -> error("Requests are handled before their outcomes")
                    }
                }
            }
        }
    }
    return calls.values.filter { it.callId !in parents }.map { parent ->
        val children = calls.values.filter { parents[it.callId] == parent.callId }
        if (children.isEmpty() && !clientMetadata.isFlowEndpoint(parent.endpoint)) parent
        else parent.copy(events = (children + listOfNotNull(parent.endBatch?.let { parent.copy(startBatch = it) })).map { call ->
            CoroutineTimelineEvent(
                call.callId, call.startBatch, call.cancellationRequestedBatch, call.endBatch,
                call.completion, call.returnValue, call.exceptionMessage, call.flowCompleted, call.argument,
            )
        }.ifEmpty { listOf(CoroutineTimelineEvent(parent.callId, parent.startBatch)) })
    }
}

context(clientMetadata: ClientMetadata)
internal fun expectedTimelineCalls(result: CoroutinePuzzleSolutionResult?): List<CoroutineTimelineExpectedCall> =
    when (result) {
        is CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure -> result.expectedFollowups.map {
            CoroutineTimelineExpectedCall(it.endPoint, it.expectedArgument, it.expectedCancellationOfCallId, it.expectedCallbackCompletionOfCallId, it.expectedCancellationCompletionOfCallId)
        }
        is CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure -> result.expectations.map {
            CoroutineTimelineExpectedCall(it.endPoint, it.expectedArgument, it.expectedCancellationOfCallId, it.expectedCallbackCompletionOfCallId, it.expectedCancellationCompletionOfCallId)
        }
        is CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure -> result.expectations.map {
            CoroutineTimelineExpectedCall(it.endPoint, it.expectedArgument, it.expectedCancellationOfCallId, it.expectedCallbackCompletionOfCallId, it.expectedCancellationCompletionOfCallId)
        }
        else -> emptyList()
    }
        .filterNot { it.endpoint.isHiddenFromHistory() }

internal fun JsonElement.isUnitValue(): Boolean = this is JsonObject && isEmpty()
