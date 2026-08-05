package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleBatchEntry
import kmpworkshop.common.CoroutinePuzzleBatchEntry.ExpectationPayload
import kmpworkshop.common.CoroutinePuzzleBatchEntry.SubmissionPayload
import kmpworkshop.common.CoroutinePuzzleEndPointDescriptor
import kmpworkshop.common.CoroutinePuzzleHistoryBatch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoroutinePuzzleTimelineTest {
    @Test
    fun `maps concurrent values throws cancellation races hanging calls and unit`() {
        val answer = CoroutinePuzzleEndPointDescriptor("answer")
        val failure = CoroutinePuzzleEndPointDescriptor("failure")
        val hanging = CoroutinePuzzleEndPointDescriptor("hanging")
        val history = listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                entry(1, SubmissionPayload.CallSubmitted(answer, JsonPrimitive(7))),
                entry(2, SubmissionPayload.CallSubmitted(failure, JsonObject(emptyMap()))),
                entry(3, SubmissionPayload.CallSubmitted(hanging, JsonNull)),
            )),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(
                entry(1, ExpectationPayload.CallAnswered(JsonObject(emptyMap()))),
                entry(2, ExpectationPayload.CallThrew("Database unavailable")),
            )),
            CoroutinePuzzleHistoryBatch.Submission(listOf(entry(2, SubmissionPayload.CallShouldCancel))),
        )

        val calls = coroutineTimeline(history)
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
        val hidden = kmpworkshop.common.callLifetime.descriptor
        val history = listOf(CoroutinePuzzleHistoryBatch.Submission(listOf(
            entry(1, SubmissionPayload.CallSubmitted(hidden, JsonNull)),
        )))
        assertTrue(coroutineTimeline(history).isEmpty())
    }

    private fun <T> entry(id: Long, payload: T) = CoroutinePuzzleBatchEntry(id, payload)
}
