package kmpworkshop.common

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CoroutinePuzzleFlowTest {
    private val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("work()")

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
    fun `collector result keeps call id around a serialized value or completion`() {
        val value: WithCallId<ValueOrCompletion<Int>> = WithCallId(42, ValueOrCompletion.Value(7))
        val completion: WithCallId<ValueOrCompletion<Int>> = WithCallId(42, ValueOrCompletion.Completion)

        assertEquals(value, Json.decodeFromString<WithCallId<ValueOrCompletion<Int>>>(Json.encodeToString(value)))
        assertEquals(completion, Json.decodeFromString<WithCallId<ValueOrCompletion<Int>>>(Json.encodeToString(completion)))
    }

    private fun successfulPuzzle(attempts: AtomicInteger): CoroutinePuzzle =
        coroutinePuzzleCommunicationChannel { expectations, submissions ->
            attempts.incrementAndGet()
            val submitted = submissions.receive()
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Batch(
                submitted.map { WithCallId(it.callId, CoroutinePuzzleExpectationPayload.CallAnswered(JsonObject(emptyMap()))) },
            ))
            expectations.send(CoroutinePuzzleExpectationBatchOrCompletion.Completion(CoroutinePuzzleSolutionResult.Success))
        }.asPuzzle()
}
