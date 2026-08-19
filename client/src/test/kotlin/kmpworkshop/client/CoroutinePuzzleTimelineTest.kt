package kmpworkshop.client

import kmpworkshop.common.WithCallId
import kmpworkshop.common.CoroutinePuzzleExpectationPayload
import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.CoroutinePuzzleEndPointId
import kmpworkshop.common.CoroutinePuzzleHistoryBatch
import kmpworkshop.common.EndpointDescriptorRegistry
import kmpworkshop.common.ValueOrCompletion
import kmpworkshop.common.descriptor
import kmpworkshop.common.flowDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object TestApis : EndpointDescriptorRegistry() {
    val answer by descriptor<Int, Unit>("answer")
    val failure by descriptor<Unit, Unit>("failure")
    val hanging by descriptor<Unit, Unit>("hanging")
    val callLifetime by descriptor<Unit, Unit>("callLifetime")
    val numbers by flowDescriptor<Unit, Int>("numbers")

    init { seal() }
}

private val testMetadata = clientMetadataOf(TestApis) {
    TestApis.callLifetime.register(isHiddenInHistory = true)
    TestApis.numbers.register(isFlowEndpoint = true)
}

class CoroutinePuzzleTimelineTest {
    @Test
    fun `maps concurrent values throws cancellation races hanging calls and unit`() {
        val answer = TestApis.answer.id
        val failure = TestApis.failure.id
        val hanging = TestApis.hanging.id
        val history = listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                entry(1, CoroutinePuzzleSubmissionPayload.CallSubmitted(answer, JsonPrimitive(7))),
                entry(2, CoroutinePuzzleSubmissionPayload.CallSubmitted(failure, JsonObject(emptyMap()))),
                entry(3, CoroutinePuzzleSubmissionPayload.CallSubmitted(hanging, JsonNull)),
            )),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(
                entry(1, CoroutinePuzzleExpectationPayload.CallAnswered(JsonObject(emptyMap()))),
                entry(2, CoroutinePuzzleExpectationPayload.CallThrew("Database unavailable")),
            )),
            CoroutinePuzzleHistoryBatch.Submission(listOf(entry(2, CoroutinePuzzleSubmissionPayload.CallShouldCancel))),
        )

        val calls = context(testMetadata) { coroutineTimeline(history) }
        assertEquals(3, calls.size)
        assertEquals(JsonPrimitive(7), calls[0].argument)
        assertTrue(calls[0].returnValue!!.isUnitValue())
        assertEquals(TimelineCompletion.THREW, calls[1].completion)
        assertEquals("Database unavailable", calls[1].exceptionMessage)
        assertEquals(2, calls[1].cancellationRequestedBatch)
        assertNull(calls[2].endBatch)
    }

    @Test
    fun `filters hidden scaffolding endpoints`() {
        val hidden = TestApis.callLifetime.id
        val history = listOf(CoroutinePuzzleHistoryBatch.Submission(listOf(
            entry(1, CoroutinePuzzleSubmissionPayload.CallSubmitted(hidden, JsonNull)),
        )))
        assertTrue(context(testMetadata) { coroutineTimeline(history) }.isEmpty())
    }

    @Test
    fun `groups every request from one flow collector in start order`() {
        val collectorArgument = JsonObject(mapOf(
            "callId" to JsonPrimitive(42),
            "payload" to JsonObject(emptyMap()),
        ))
        val history = listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                entry(1, CoroutinePuzzleSubmissionPayload.CallSubmitted(TestApis.numbers.id, collectorArgument)),
            )),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(
                entry(1, CoroutinePuzzleExpectationPayload.CallAnswered(JsonPrimitive(7))),
            )),
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                entry(2, CoroutinePuzzleSubmissionPayload.CallSubmitted(TestApis.numbers.id, collectorArgument)),
            )),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(
                entry(2, CoroutinePuzzleExpectationPayload.CallAnswered(JsonPrimitive(8))),
            )),
        )

        val flowCall = context(testMetadata) { coroutineTimeline(history) }.single()
        assertEquals(listOf(0, 2), flowCall.events.map { it.startBatch })
        assertEquals(listOf(1, 3), flowCall.events.map { it.endBatch })
        assertTrue(flowCall.events.none { it.flowCompleted })
    }

    @Test
    fun `recognizes when a flow collector has actually completed`() {
        val collectorArgument = JsonObject(mapOf(
            "callId" to JsonPrimitive(42),
            "payload" to JsonObject(emptyMap()),
        ))
        val completion: WithCallId<ValueOrCompletion<Int>> = WithCallId(42, ValueOrCompletion.Completion)
        val history = listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                entry(1, CoroutinePuzzleSubmissionPayload.CallSubmitted(TestApis.numbers.id, collectorArgument)),
            )),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(
                entry(1, CoroutinePuzzleExpectationPayload.CallAnswered(Json.encodeToJsonElement(completion))),
            )),
        )

        assertTrue(context(testMetadata) { coroutineTimeline(history) }.single().events.single().flowCompleted)
    }

    private fun <T> entry(id: Long, payload: T) = WithCallId(id, payload)
}
