package kmpworkshop.common

import kmpworkshop.common.CoroutinePuzzleCallPayload.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CoroutinePuzzleFlowTest {
    private object TestApis : EndpointDescriptorRegistry() {
        val work by descriptor<Unit, Unit>("work()")
        val callback by higherOrderDescriptor<Unit, Int, String, String>("callback()")

        init { seal() }
    }

    private val endpoint = TestApis.work

    @Test
    fun `a producer failure or cancellation needs no acknowledgement`(): Unit = runBlocking {
        for (outcome in listOf(Outcome.Threw("Producer failed"), Outcome.Cancelled)) {
            coroutinePuzzleCommunicationChannel { expectations, submissions ->
                val request = submissions.receive().single()
                expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Batch(listOf(WithCallId(request.callId, outcome))))
                assertEquals(true, submissions.receiveCatching().isClosed)
                expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Completion(CoroutinePuzzleSolutionResult.Success))
            }.solve {
                val result = runCatching { endpoint.submitCall(Unit) }
                assertEquals(true, result.isFailure)
            }.also { assertIs<CoroutinePuzzleSolutionResult.Success>(it.result) }
        }
    }

    @Test
    fun `returning a value needs no acknowledgement before the next call`(): Unit = runBlocking {
        var returned = false
        val unit = JsonObject(emptyMap())
        coroutinePuzzleCommunicationChannel { expectations, submissions ->
            val first = submissions.receive().single()
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Batch(listOf(
                WithCallId(first.callId, Outcome.Returned(unit)),
            )))
            val next = submissions.receive()
            assertEquals(true, returned)
            assertEquals(1, next.size)
            assertIs<CoroutinePuzzleCallPayload.CallSubmitted>(next.last().payload)
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Batch(listOf(
                WithCallId(next.last().callId, Outcome.Returned(unit)),
            )))
            assertEquals(true, submissions.receiveCatching().isClosed)
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Completion(CoroutinePuzzleSolutionResult.Success))
        }.solve {
            endpoint.submitCall(Unit)
            returned = true
            endpoint.submitCall(Unit)
        }.also { assertIs<CoroutinePuzzleSolutionResult.Success>(it.result) }
    }

    @Test
    fun `callback completion publishes one settled turn without an acknowledgement`(): Unit = runBlocking {
        coroutinePuzzleCommunicationChannel { expectations, submissions ->
            val parent = submissions.receive().single()
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Batch(listOf(
                WithCallId(-1, CoroutinePuzzleCallPayload.CallbackInvoked(parent.callId, Json.encodeToJsonElement(7))),
            )))
            assertEquals(listOf(WithCallId(-1, Outcome.Returned(Json.encodeToJsonElement("value 7")))), submissions.receive())
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Batch(listOf(
                WithCallId(parent.callId, Outcome.Returned(Json.encodeToJsonElement("done"))),
            )))
            assertEquals(true, submissions.receiveCatching().isClosed)
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Completion(CoroutinePuzzleSolutionResult.Success))
        }.solve {
            assertEquals("done", TestApis.callback.submitCall { "value $it" })
        }.also { assertIs<CoroutinePuzzleSolutionResult.Success>(it.result) }
    }

    @Test
    fun `final cancellation outcome is flushed before submissions close`(): Unit = runBlocking {
        coroutinePuzzleCommunicationChannel { expectations, submissions ->
            val parent = submissions.receive().single()
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Batch(listOf(
                WithCallId(parent.callId, CoroutinePuzzleCallPayload.Cancel),
            )))
            assertEquals(listOf(WithCallId(parent.callId, Outcome.Cancelled)), submissions.receive())
            assertEquals(true, submissions.receiveCatching().isClosed)
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Completion(CoroutinePuzzleSolutionResult.Success))
        }.solve {
            assertIs<CancellationAcrossRpc>(runCatching { TestApis.callback.submitCall { error("No invocation") } }.exceptionOrNull())
        }.also { assertIs<CoroutinePuzzleSolutionResult.Success>(it.result) }
    }

    @Test
    fun `typed callbacks serialize their inputs and outputs independently of the outer result`(): Unit = runBlocking {
        val scope = object : CoroutinePuzzleSolutionScope {
            override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement = t
            override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(
                topic: JsonElement,
                function: suspend CoroutineScope.(JsonElement) -> JsonElement,
            ): JsonElement = coroutineScope {
                assertEquals(TestApis.callback, this@submitRawCall)
                assertEquals(JsonObject(emptyMap()), topic)
                assertEquals(Json.encodeToJsonElement("value 7"), function(Json.encodeToJsonElement(7)))
                assertEquals(Json.encodeToJsonElement("value 8"), function(Json.encodeToJsonElement(8)))
                Json.encodeToJsonElement("outer result")
            }
        }
        context(scope) {
            assertEquals(Unit, endpoint.submitCall(Unit))
            assertEquals("outer result", TestApis.callback.submitCall { "value $it" })
        }
    }

    @Test
    fun `flow emits each history batch and one completion`(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val puzzle = successfulPuzzle(attempts)

        val states = puzzle.solveAsFlow { endpoint.submitCall(Unit) }.toList()

        assertEquals(1, attempts.get())
        assertEquals(3, states.size)
        assertIs<CoroutinePuzzleSolveState.Running>(states.first())
        assertIs<CoroutinePuzzleHistoryBatch.Submission>((states[0] as CoroutinePuzzleSolveState.Running).batch)
        assertIs<CoroutinePuzzleHistoryBatch.Expectation>((states[1] as CoroutinePuzzleSolveState.Running).batch)
        assertEquals(1, states.count { it is CoroutinePuzzleSolveState.Completed })
        assertIs<CoroutinePuzzleSolutionResult.Success>((states.last() as CoroutinePuzzleSolveState.Completed).result)
    }

    @Test
    fun `compatibility solve returns final result and history`(): Unit = runBlocking {
        val solved = successfulPuzzle(AtomicInteger()).solve { endpoint.submitCall(Unit) }
        assertIs<CoroutinePuzzleSolutionResult.Success>(solved.result)
        assertEquals(2, solved.history.size)
    }

    @Test
    fun `each collection creates a separate attempt`(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val flow = successfulPuzzle(attempts).solveAsFlow { endpoint.submitCall(Unit) }
        flow.toList()
        flow.toList()
        assertEquals(2, attempts.get())
    }

    @Test
    fun `cancelling collection cancels active attempt`(): Unit = runBlocking {
        val cancelled = AtomicInteger()
        val puzzle = coroutinePuzzleCommunicationChannel { _, _ ->
            try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }.asPuzzle()

        puzzle.solveAsFlow { endpoint.submitCall(Unit) }.take(1).toList()
        assertEquals(1, cancelled.get())
    }

    @Test
    fun `callback invocations preserve their enclosing call identity through serialization`() {
        val invocation: WithCallId<CoroutinePuzzleExpectationPayload> = WithCallId(-1,
            CoroutinePuzzleCallPayload.CallbackInvoked(42, Json.encodeToJsonElement(7)))
        assertEquals(invocation, Json.decodeFromString<WithCallId<CoroutinePuzzleExpectationPayload>>(Json.encodeToString(invocation)))
    }

    @Test
    fun `higher order call protocol survives serialization`() {
        val submissions = listOf(
            CoroutinePuzzleCallPayload.CallSubmitted(endpoint.id, JsonObject(emptyMap())),
            CoroutinePuzzleCallPayload.HigherOrderCallSubmitted(TestApis.callback.id,
                kotlinx.serialization.json.buildJsonObject { put("first", JsonObject(emptyMap())); put("second", kotlinx.serialization.json.JsonPrimitive(42)) }),
            CoroutinePuzzleCallPayload.Cancel,
            Outcome.Returned(JsonObject(emptyMap())),
            Outcome.Threw("Consumer failed"),
            Outcome.Cancelled,
        )
        submissions.forEach { payload ->
            assertEquals(payload, Json.decodeFromString<CoroutinePuzzleSubmissionPayload>(Json.encodeToString(payload)))
        }
        val expectations = listOf<CoroutinePuzzleExpectationPayload>(
            CoroutinePuzzleCallPayload.CallbackInvoked(42, Json.encodeToJsonElement(7)),
            CoroutinePuzzleCallPayload.Cancel,
            Outcome.Returned(JsonObject(emptyMap())),
            Outcome.Threw("Producer failed"),
            Outcome.Cancelled,
        )
        expectations.forEach { payload ->
            assertEquals(payload, Json.decodeFromString<CoroutinePuzzleExpectationPayload>(Json.encodeToString(payload)))
        }
        // Control messages really are shared, rather than two equivalent direction-specific encodings.
        listOf(CoroutinePuzzleCallPayload.Cancel, Outcome.Cancelled, Outcome.Threw("Failure")).forEach { payload ->
            assertEquals(
                Json.encodeToString<CoroutinePuzzleSubmissionPayload>(payload),
                Json.encodeToString<CoroutinePuzzleExpectationPayload>(payload),
            )
        }
    }

    private fun successfulPuzzle(attempts: AtomicInteger): CoroutinePuzzle =
        coroutinePuzzleCommunicationChannel { expectations, submissions ->
            attempts.incrementAndGet()
            val submitted = submissions.receive()
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Batch(
                submitted.map { WithCallId(it.callId, Outcome.Returned(JsonObject(emptyMap()))) },
            ))
            assertEquals(true, submissions.receiveCatching().isClosed)
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Completion(CoroutinePuzzleSolutionResult.Success))
        }.asPuzzle()
}
