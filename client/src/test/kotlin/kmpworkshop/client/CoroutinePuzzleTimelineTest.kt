package kmpworkshop.client

import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleCallPayload.Outcome
import kmpworkshop.server.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

private object TestApis : EndpointDescriptorRegistry() {
    val answer by descriptor<Int, Unit>("answer")
    val failure by descriptor<Unit, Unit>("failure")
    val hanging by descriptor<Unit, Unit>("hanging")
    val callLifetime by descriptor<Unit, Unit>("callLifetime")
    val numbers by flowDescriptor<Unit, Int>("numbers")
    val transform by higherOrderDescriptor<String, Int, String, Boolean>("transform")

    init { seal() }
}

private val testMetadata = clientMetadataOf(TestApis) {
    TestApis.callLifetime.register(isHiddenInHistory = true)
    TestApis.numbers.register(isFlowEndpoint = true)
}

private val testServerMetadata = serverMetadataOf(TestApis) {
    TestApis.numbers.register(flowFunctionCall = "numbers")
}

class CoroutinePuzzleTimelineTest {
    @Test
    fun `generic callbacks retain their own arguments and results`() {
        val history = listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                WithCallId(1, CoroutinePuzzleCallPayload.HigherOrderCallSubmitted(TestApis.transform.id,
                    kotlinx.serialization.json.JsonObject(mapOf("first" to JsonPrimitive("topic"), "second" to JsonPrimitive(1))))),
            )),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(
                WithCallId(-1, CoroutinePuzzleCallPayload.CallbackInvoked(1, JsonPrimitive(7))),
            )),
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                WithCallId(-1, Outcome.Returned(JsonPrimitive("seven"))),
            )),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(
                WithCallId(1, Outcome.Returned(JsonPrimitive(true))),
            )),
        )
        val call = context(testMetadata) { coroutineTimeline(history).single() }
        assertEquals(JsonPrimitive("topic"), call.argument)
        assertEquals(JsonPrimitive(true), call.returnValue)
        val callback = call.events.single { it.callId == -1L }
        assertEquals(JsonPrimitive(7), callback.argument)
        assertEquals(JsonPrimitive("seven"), callback.returnValue)
        assertEquals(TimelineCompletion.RETURNED, callback.completion)
    }

    @Test
    fun `expectation requested cancellation stays open until submission cleanup completes`() {
        context(testMetadata) {
            val submitted = CoroutinePuzzleHistoryBatch.Submission(listOf(
                WithCallId(99, CoroutinePuzzleCallPayload.CallSubmitted(TestApis.answer.id, JsonPrimitive(1))),
            ))
            val cancelled = CoroutinePuzzleHistoryBatch.Expectation(listOf(
                WithCallId(99, CoroutinePuzzleCallPayload.Cancel),
            ))
            // Before and after an answer, cancellation ends with exactly the same outcome.
            listOf(listOf(submitted, cancelled)).forEach { pending ->
                assertNull(coroutineTimeline(pending).single().endBatch)
                assertEquals(pending.lastIndex, coroutineTimeline(pending).single().cancellationRequestedBatch)
                val failure = CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure(listOf(
                    CoroutinePuzzleExpectedFollowup(TestApis.answer.id, expectedCancellationCompletionOfCallId = 99),
                ))
                val expected = expectedTimelineCalls(failure)
                assertEquals(mapOf(99L to expected.single()), placeExpectedCalls(coroutineTimeline(pending), expected).alignedByCallId)
                assertTrue("Finish cancelling" in failure.toMessage())
                val completed = pending + CoroutinePuzzleHistoryBatch.Submission(listOf(WithCallId(99, Outcome.Cancelled)))
                val call = coroutineTimeline(completed).single()
                assertEquals(pending.size, call.endBatch)
                assertEquals(TimelineCompletion.CANCELLED, call.completion)
                assertTrue('c' in renderCoroutinePuzzleHistory(completed))
            }
        }
    }

    @Test
    fun `higher order call contains separately completed callback invocations`() {
        context(testMetadata) {
            val submission = CoroutinePuzzleHistoryBatch.Submission(listOf(
                WithCallId(99, CoroutinePuzzleCallPayload.HigherOrderCallSubmitted(TestApis.numbers.id,
                    kotlinx.serialization.json.JsonObject(mapOf("first" to kotlinx.serialization.json.JsonObject(emptyMap()), "second" to JsonPrimitive(99))))),
            ))
            val invocation = CoroutinePuzzleHistoryBatch.Expectation(listOf(
                WithCallId(-1, CoroutinePuzzleCallPayload.CallbackInvoked(99, JsonPrimitive(7))),
            ))
            val pending = listOf(submission, invocation)
            val call = coroutineTimeline(pending).single()
            assertNull(call.endBatch)
            assertEquals(JsonPrimitive(7), call.events.single().returnValue)
            val expected = expectedTimelineCalls(CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure(
                listOf(CoroutinePuzzleExpectedFollowup(TestApis.numbers.id, expectedCallbackCompletionOfCallId = -1)),
            ))
            assertEquals(mapOf(99L to expected.single()), placeExpectedCalls(listOf(call), expected).alignedByCallId)
            val completed = pending + CoroutinePuzzleHistoryBatch.Submission(listOf(
                WithCallId(-1, Outcome.Returned(kotlinx.serialization.json.JsonObject(emptyMap()))),
            ))
            assertNull(coroutineTimeline(completed).single().endBatch)
            assertEquals(2, coroutineTimeline(completed).single().events.single().endBatch)
        }
    }

    @Test
    fun `maps actual returned and thrown calls`() = runBlocking {
        val attempt = coroutinePuzzle(testServerMetadata) {
            TestApis.answer.expectCall(Unit)
            TestApis.failure.expectThrowingCall("Database unavailable")
        }.asPuzzle().solveAsFlow {
            TestApis.answer.submitCall(7)
            runCatching { TestApis.failure.submitCall(Unit) }
        }.toResultWithHistory()

        val calls = context(testMetadata) { coroutineTimeline(attempt.history) }

        assertEquals(2, calls.size)
        assertEquals(JsonPrimitive(7), calls.single { it.endpoint == TestApis.answer.id }.argument)
        assertTrue(calls.single { it.endpoint == TestApis.answer.id }.returnValue!!.isUnitValue())
        assertEquals(TimelineCompletion.THREW, calls.single { it.endpoint == TestApis.failure.id }.completion)
        assertEquals("Database unavailable", calls.single { it.endpoint == TestApis.failure.id }.exceptionMessage)
    }

    @Test
    fun `maps an actual cancelled call`() = runBlocking {
        val attempt = coroutinePuzzle(testServerMetadata) {
            TestApis.hanging.expectCanceledCall {
                TestApis.callLifetime.expectCall(Unit)
                expectCancellation()
            }
        }.asPuzzle().solveAsFlow {
            launch { TestApis.hanging.submitCall(Unit) }.sideEffect { hangingCall ->
                TestApis.callLifetime.submitCall(Unit)
                hangingCall.cancel()
            }
        }.toResultWithHistory()

        val call = context(testMetadata) { coroutineTimeline(attempt.history) }.single()

        assertIs<CoroutinePuzzleSolutionResult.Success>(attempt.result)
        assertEquals(TestApis.hanging.id, call.endpoint)
        assertEquals(TimelineCompletion.CANCELLED, call.completion)
    }

    @Test
    fun `reports an expected cancellation after another unexpected request`() = runBlocking {
        val attempt = coroutinePuzzle(testServerMetadata) {
            TestApis.hanging.expectCanceledCall {
                TestApis.callLifetime.expectCall(Unit)
                expectCancellation()
            }
        }.asPuzzle().solveAsFlow {
            launch { TestApis.hanging.submitCall(Unit) }.sideEffect {
                TestApis.callLifetime.submitCall(Unit)
                TestApis.answer.submitCall(7)
            }
        }.toResultWithHistory()

        assertIs<CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure>(attempt.result)
        val expectedCall = context(testMetadata) { expectedTimelineCalls(attempt.result) }.single()
        val hangingCall = context(testMetadata) { coroutineTimeline(attempt.history) }
            .single { it.endpoint == TestApis.hanging.id }
        assertEquals(hangingCall.callId, expectedCall.expectedCancellationOfCallId)
    }

    @Test
    fun `aligns an expected cancellation with its outer collection`() = runBlocking {
        val attempt = coroutinePuzzle(testServerMetadata) {
            TestApis.numbers.expectCanceledCall { _, callback ->
                callback(7)
                TestApis.callLifetime.expectCall(Unit)
                expectCancellation()
            }
        }.asPuzzle().solveAsFlow {
            launch { TestApis.numbers.submitCall { } }.sideEffect {
                TestApis.callLifetime.submitCall(Unit)
                TestApis.answer.submitCall(9)
            }
        }.toResultWithHistory()
        assertIs<CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure>(attempt.result)
        val calls = context(testMetadata) { coroutineTimeline(attempt.history) }
        val expectedCalls = context(testMetadata) { expectedTimelineCalls(attempt.result) }
        val flowCall = calls.single { it.endpoint == TestApis.numbers.id }
        assertEquals(flowCall.callId, expectedCalls.single().expectedCancellationOfCallId)
        assertEquals(expectedCalls.single(), placeExpectedCalls(calls, expectedCalls).alignedByCallId[flowCall.callId])
    }

    @Test
    fun `maps an actual open unmatched submission`() = runBlocking {
        val attempt = coroutinePuzzle(testServerMetadata) {
            awaitQuiescenceAndGetUnmatchedSubmissions()
        }.asPuzzle().solveAsFlow {
            TestApis.hanging.submitCall(Unit)
        }.toResultWithHistory()

        val call = context(testMetadata) { coroutineTimeline(attempt.history) }.single()

        assertIs<CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure>(attempt.result)
        assertEquals(TestApis.hanging.id, call.endpoint)
        assertNull(call.endBatch)
    }

    @Test
    fun `filters hidden scaffolding calls and expectations from actual puzzles`() = runBlocking {
        val attempt = coroutinePuzzle(testServerMetadata) {
            TestApis.callLifetime.expectCall(Unit)
        }.asPuzzle().solveAsFlow {
            TestApis.callLifetime.submitCall(Unit)
        }.toResultWithHistory()
        val expectedAttempt = coroutinePuzzle(testServerMetadata) {
            TestApis.callLifetime.expectCall(Unit)
        }.asPuzzle().solveAsFlow { }.toResultWithHistory()

        assertIs<CoroutinePuzzleSolutionResult.Success>(attempt.result)
        assertTrue(context(testMetadata) { coroutineTimeline(attempt.history) }.isEmpty())
        assertTrue(context(testMetadata) { expectedTimelineCalls(expectedAttempt.result) }.isEmpty())
    }

    @Test
    fun `groups every request from one actual flow collector and recognizes completion`() = runBlocking {
        val attempt = coroutinePuzzle(testServerMetadata) {
            TestApis.numbers.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) ->
                    emit(7)
                    emit(8)
                }
            }
        }.asPuzzle().solveAsFlow {
            val values = TestApis.numbers.asFlow().let { flow -> flow.toList() }
            assertEquals(listOf(7, 8), values)
        }.toResultWithHistory()

        val flowCall = context(testMetadata) { coroutineTimeline(attempt.history) }.single()

        assertIs<CoroutinePuzzleSolutionResult.Success>(attempt.result)
        assertEquals(3, flowCall.events.size)
        assertTrue(flowCall.events.zipWithNext().all { (left, right) -> left.startBatch < right.startBatch })
        assertTrue(flowCall.events.dropLast(1).none { it.flowCompleted })
        assertTrue(flowCall.events.last().flowCompleted)
    }

    @Test
    fun `derives an expected next flow emission argument from an actual puzzle`() = runBlocking {
        val attempt = coroutinePuzzle(testServerMetadata) {
            TestApis.numbers.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) ->
                    emit(7)
                    emit(8)
                }
            }
        }.asPuzzle().solveAsFlow {
            TestApis.numbers.asFlow().let { flow -> flow.take(1).toList() }
        }.toResultWithHistory()

        assertIs<CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure>(attempt.result)
        val expectedCall = context(testMetadata) { expectedTimelineCalls(attempt.result) }.single()
        val flowCall = context(testMetadata) { coroutineTimeline(attempt.history) }.single()
        assertEquals(TestApis.numbers.id, expectedCall.endpoint)
        assertEquals(flowCall.argument, expectedCall.expectedArgument)
    }
}
