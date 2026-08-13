package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleExpectationPayload
import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.CoroutinePuzzleEndPointDescriptor
import kmpworkshop.common.CoroutinePuzzleHistoryBatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class TimelineCompletion { RETURNED, THREW, CANCELLED }

data class CoroutineTimelineCall(
    val callId: Long,
    val endpoint: CoroutinePuzzleEndPointDescriptor,
    val argument: JsonElement,
    val startBatch: Int,
    val cancellationRequestedBatch: Int? = null,
    val endBatch: Int? = null,
    val completion: TimelineCompletion? = null,
    val returnValue: JsonElement? = null,
    val exceptionMessage: String? = null,
)

fun coroutineTimeline(batches: List<CoroutinePuzzleHistoryBatch>): List<CoroutineTimelineCall> {
    val calls = linkedMapOf<Long, CoroutineTimelineCall>()
    batches.forEachIndexed { batchIndex, batch ->
        when (batch) {
            is CoroutinePuzzleHistoryBatch.Submission -> batch.entries.forEach { (id, payload) ->
                when (payload) {
                    is CoroutinePuzzleSubmissionPayload.CallSubmitted -> if (!payload.endPoint.isHiddenFromHistory()) {
                        check(id !in calls) { "Call $id was submitted more than once" }
                        calls[id] = CoroutineTimelineCall(id, payload.endPoint, payload.arg, batchIndex)
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
                            returnValue = payload.result,
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
    return calls.values.toList()
}

internal fun JsonElement.isUnitValue(): Boolean = this is JsonObject && isEmpty()
