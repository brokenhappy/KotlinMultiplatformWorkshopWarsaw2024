package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.EndpointDescriptorRegistry
import kmpworkshop.common.asPuzzle
import kmpworkshop.common.descriptor
import kmpworkshop.common.flowDescriptor
import kmpworkshop.common.sideEffect
import kmpworkshop.common.submitCall
import kmpworkshop.common.toResultWithHistory
import kmpworkshop.server.awaitQuiescenceAndGetUnmatchedSubmissions
import kmpworkshop.server.coroutinePuzzle
import kmpworkshop.server.expectCall
import kmpworkshop.server.expectCanceledCall
import kmpworkshop.server.expectThrowingCall
import kmpworkshop.server.expectingFlowCollector
import kmpworkshop.server.serverMetadataOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

private val testServerMetadata = serverMetadataOf(TestApis) {
    TestApis.numbers.register(flowFunctionCall = "numbers")
}

class CoroutinePuzzleTimelineTest {
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
                awaitCancellation()
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
            val values = TestApis.numbers.asFlows().use { flow -> flow.toList() }
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
            TestApis.numbers.asFlows().use { flow -> flow.take(1).toList() }
        }.toResultWithHistory()

        assertIs<CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure>(attempt.result)
        val expectedCall = context(testMetadata) { expectedTimelineCalls(attempt.result) }.single()
        val flowCall = context(testMetadata) { coroutineTimeline(attempt.history) }.single()
        assertEquals(TestApis.numbers.id, expectedCall.endpoint)
        assertEquals(flowCall.argument, expectedCall.expectedArgument)
    }
}
