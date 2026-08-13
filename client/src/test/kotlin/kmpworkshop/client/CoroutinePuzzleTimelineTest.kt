package kmpworkshop.client

import kmpworkshop.common.WithCallId
import kmpworkshop.common.CoroutinePuzzleExpectationPayload
import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.CoroutinePuzzleEndPointId
import kmpworkshop.common.CoroutinePuzzleHistoryBatch
import kmpworkshop.common.EndpointDescriptorRegistry
import kmpworkshop.common.descriptor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object TestApis : EndpointDescriptorRegistry() {
    val answer by descriptor<Int, Unit>("answer")
    val failure by descriptor<Unit, Unit>("failure")
    val hanging by descriptor<Unit, Unit>("hanging")
    val callLifetime by descriptor<Unit, Unit>("callLifetime")

    init { seal() }
}

private val testMetadata = clientMetadataOf(TestApis) {
    TestApis.callLifetime.register(isHiddenInHistory = true)
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

    private fun <T> entry(id: Long, payload: T) = WithCallId(id, payload)
}
