package com.kotlinworkshop.test

import kmpworkshop.client.*
import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleCallPayload.Outcome
import kmpworkshop.server.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kmpworkshop.common.CoroutinePuzzleResultWithHistory as ResultsWHistory
import org.junit.jupiter.api.fail as junitFail

private object TestApis : EndpointDescriptorRegistry() {
    val flowUnitInt by flowDescriptor<Unit, Int>("numbers()")
    val flowStringInt by flowDescriptor<String, Int>("next value")
    val unit by descriptor<Unit, Unit>("foo")
    val callbackUnit by higherOrderDescriptor<Unit, Unit, Unit, Unit>("foo")
    val callbackInt by higherOrderDescriptor<Int, Int, String, String>("callback int")
    val callLifetime by descriptor<Unit, Unit>("lifetime")
    val bar by descriptor<Unit, Unit>("bar")
    val alreadyExpected by descriptor<Unit, Unit>("already expected")
    val discovered by descriptor<Unit, Unit>("discovered")
    val outer by descriptor<Unit, Unit>("outer")
    val nested by descriptor<Unit, Unit>("nested")
    val intInt by descriptor<Int, Int>("foo")
    val intString by descriptor<Int, String>("foo")

    init { seal() }
}

private val testServerMetadata = serverMetadataOf(TestApis) { }
private val testClientMetadata = clientMetadataOf(TestApis) {
    TestApis.flowUnitInt.register(isFlowEndpoint = true)
    TestApis.flowStringInt.register(isFlowEndpoint = true)
    TestApis.unit.register()
    TestApis.callbackUnit.register()
    TestApis.callbackInt.register()
    TestApis.callLifetime.register(isHiddenInHistory = true)
    TestApis.bar.register()
    TestApis.alreadyExpected.register()
    TestApis.discovered.register()
    TestApis.outer.register()
    TestApis.nested.register()
    TestApis.intInt.register()
    TestApis.intString.register()
}

private fun coroutinePuzzle(
    builder: suspend context(CoroutinePuzzleBuilderScope) CoroutineScope.() -> Unit,
): Resource<CoroutinePuzzleProtocol> = context(testServerMetadata) {
    coroutinePuzzleWithMetadata(builder)
}

context(builder: CoroutinePuzzleBuilderScope)
private suspend fun expectIntArgument(argument: Int): Int = builder.expectCallTo(
    TestApis.intInt,
    serializer(),
    serializer(),
    expectedArgument = ExpectedArgument.Exact(argument),
) { it + 10 }

private fun CoroutinePuzzleSolutionResult.renderClientMessage(): String =
    context(testClientMetadata) { toMessage() }

private fun ResultsWHistory.renderClientMessage(): String =
    try {
        context(testClientMetadata) { toMessage() }
    } catch (_: MetadataNotFoundException) {
        context(defaultClientMetadata) { toMessage() }
    }

class CoroutinePuzzleTest {
    @Test
    fun `cancelling a producer in noncancellable work still advances quiescence`() = runTestWithRandomizedDispatchOrdering {
        var cleanedUp = false
        coroutinePuzzle {
            TestApis.bar.expectCanceledCall {
                withContext(NonCancellable) {
                    TestApis.unit.expectCall(Unit)
                    awaitQuiescenceAndVerifyUnmatchedSubmissions()
                    cleanedUp = true
                }
                expectCancellation()
            }
        }.solve {
            val call = launch { TestApis.bar.submitCall(Unit) }
            TestApis.unit.submitCall(Unit)
            call.cancelAndJoin()
            assertEquals(true, cleanedUp)
        }.assertIsOk()
    }

    @Test
    fun `higher order calls can invoke the callback zero or many times`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            TestApis.callbackInt.expectCall { argument, _ ->
                assertEquals(0, argument)
                "no callbacks"
            }
            TestApis.callbackInt.expectCall { argument, callback ->
                repeat(argument) { assertEquals("value $it", callback(it)) }
                "done"
            }
        }.solve {
            assertEquals("no callbacks", TestApis.callbackInt.submitCall(0) { error("Must not run") })
            assertEquals("done", TestApis.callbackInt.submitCall(3) { "value $it" })
        }.assertIsOk()
    }

    @Test
    fun `a retained callback cannot start work after its parent returns`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            var retained: (suspend (Int) -> String)? = null
            TestApis.callbackInt.expectCall { _, callback ->
                retained = callback
                "done"
            }
            assertThrows<CancellationException> { checkNotNull(retained)(1) }
        }.solve {
            assertEquals("done", TestApis.callbackInt.submitCall(0) { error("The parent call has returned") })
        }.assertIsOk()
    }

    @Test
    fun `generic callbacks may overlap and route their own results`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            TestApis.callbackInt.expectCall { _, callback ->
                coroutineScope {
                    launch { assertEquals("one", callback(1)) }
                    launch { assertEquals("two", callback(2)) }
                    awaitQuiescenceAndVerifyUnmatchedSubmissions(TestApis.bar, TestApis.nested)
                    launch { TestApis.bar.expectCall(Unit) }
                    TestApis.nested.expectCall(Unit)
                }
                "done"
            }
        }.solve {
            assertEquals("done", TestApis.callbackInt.submitCall(0) {
                if (it == 1) { TestApis.bar.submitCall(Unit); "one" }
                else { TestApis.nested.submitCall(Unit); "two" }
            })
        }.assertIsOk()
    }

    @Test
    fun `a callback failure can be caught without ending the outer call`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            TestApis.callbackInt.expectCall { _, callback ->
                assertEquals("callback failed", assertThrows<ExceptionAcrossRpc> { callback(1) }.message)
                assertEquals("recovered", callback(2))
                "outer result"
            }
        }.solve {
            assertEquals("outer result", TestApis.callbackInt.submitCall(0) {
                if (it == 1) throw ExceptionAcrossRpc("callback failed") else "recovered"
            })
        }.assertIsOk()
    }

    @Test
    fun `cancelling one callback joins cleanup and leaves the outer call usable`() = runTestWithRandomizedDispatchOrdering {
        var cleanedUp = false
        coroutinePuzzle {
            TestApis.callbackInt.expectCall { _, callback ->
                coroutineScope invocations@ {
                    val first = launch { callback(1) }
                    TestApis.bar.expectCanceledCall {
                        this@invocations.launch { first.cancelAndJoin() }
                        expectCancellation()
                    }
                    first.join()
                    assertEquals(true, cleanedUp)
                }
                assertEquals("second", callback(2))
                "done"
            }
        }.solve {
            assertEquals("done", TestApis.callbackInt.submitCall(0) {
                if (it == 1) {
                    try { TestApis.bar.submitCall(Unit) } finally { cleanedUp = true }
                    "first"
                } else "second"
            })
        }.assertIsOk()
    }

    @Test
    fun `mixed producer outcomes stay associated with their submissions across repeated batches`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            repeat(3) { index ->
                coroutineScope {
                    launch { TestApis.unit.expectCall(Unit) }
                    launch { TestApis.bar.expectThrowingCall("Failure $index") }
                    TestApis.nested.expectCanceledCall { throw CancellationAcrossRpc() }
                }
            }
        }.solve {
            repeat(3) { index ->
                coroutineScope {
                    launch { TestApis.unit.submitCall(Unit) }
                    launch {
                        val failure = assertThrows<ExceptionAcrossRpc> { TestApis.bar.submitCall(Unit) }
                        assertEquals("Failure $index", failure.message)
                    }
                    assertThrows<CancellationAcrossRpc> { TestApis.nested.submitCall(Unit) }
                }
            }
        }.assertIsOk()
    }

    @Test
    fun `a completion cancellation and new submission in one batch advance their own calls`() = runTestWithRandomizedDispatchOrdering {
        val unit = Json.encodeToJsonElement(serializer<Unit>(), Unit)
        coroutinePuzzle {
            coroutineScope {
                launch { TestApis.unit.expectCall(Unit) }
                TestApis.bar.expectCanceledCall { expectCancellation() }
            }
            TestApis.nested.expectCall(Unit)
        }.use { (expectations, submissions) ->
            submissions.send(listOf(
                WithCallId(1, CoroutinePuzzleCallPayload.CallSubmitted(TestApis.unit.id, unit)),
                WithCallId(2, CoroutinePuzzleCallPayload.CallSubmitted(TestApis.bar.id, unit)),
            ))
            expectations.receive().assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Batch>()
                .batch.assertEquals(listOf(WithCallId(1, Outcome.Returned(unit))))

            submissions.send(listOf(
                WithCallId(2, CoroutinePuzzleCallPayload.Cancel),
                WithCallId(3, CoroutinePuzzleCallPayload.CallSubmitted(TestApis.nested.id, unit)),
            ))
            expectations.receive().assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Batch>()
                .batch.assertEquals(listOf(WithCallId(2, Outcome.Cancelled)))

            submissions.send(emptyList())
            expectations.receive().assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Batch>()
                .batch.assertEquals(listOf(WithCallId(3, Outcome.Returned(unit))))

            submissions.close()
            expectations.receive().assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Completion>()
                .result.assertIs<CoroutinePuzzleSolutionResult.Success>()
        }
    }

    @Test
    fun `cancellation racing a producer failure preserves the failure without invoking the callback`() = runTestWithRandomizedDispatchOrdering {
        var callbackRan = false
        coroutinePuzzle {
            launch { TestApis.callbackUnit.expectThrowingCall("Producer failed") }
            TestApis.bar.expectCall(Unit)
        }.solve {
            val call = launch {
                runCatching { TestApis.callbackUnit.submitCall { callbackRan = true } }
            }
            TestApis.bar.submitCall(Unit)
            call.cancelAndJoin()
        }.assertIsOk()
        assertEquals(false, callbackRan)
    }

    @Test
    fun `bidirectional cancellation and cleanup survive a serialized flow transport`() = runTestWithRandomizedDispatchOrdering {
        val puzzle = coroutinePuzzle {
            coroutineScope control@ {
                val expectation = launch { TestApis.callbackUnit.expectCall { _, callback -> callback(Unit) } }
                TestApis.bar.expectCanceledCall {
                    this@control.launch { expectation.cancelAndJoin() }
                    expectCancellation()
                }
                TestApis.nested.expectCall(Unit)
            }
        }
        mapFlowsToCommunicationProtocol<CoroutinePuzzleExpectationBatchOrCompletion, CoroutinePuzzleBatch<CoroutinePuzzleSubmissionPayload>> { submissions ->
            puzzle.communicateAsFlows(submissions.map {
                Json.decodeFromString<CoroutinePuzzleBatch<CoroutinePuzzleSubmissionPayload>>(Json.encodeToString(it))
            }).map {
                Json.decodeFromString<CoroutinePuzzleExpectationBatchOrCompletion>(Json.encodeToString(it))
            }
        }.solve {
            assertThrows<CancellationAcrossRpc> {
                TestApis.callbackUnit.submitCall {
                    try {
                        TestApis.bar.submitCall(Unit)
                    } finally {
                        importantCleanup { TestApis.nested.submitCall(Unit) }
                    }
                }
            }
        }.assertIsOk()
    }

    @Test
    fun `producer cancellation is a cancellation result and never invokes the callback`() = runTestWithRandomizedDispatchOrdering {
        val result = coroutinePuzzle {
            TestApis.callbackUnit.expectCanceledCall { _, _ -> throw CancellationAcrossRpc() }
        }.solve {
            assertThrows<CancellationAcrossRpc> {
                TestApis.callbackUnit.submitCall { error("The callback must not run") }
            }
        }
        result.assertIsOk()
        assertContains(result.renderClientMessage(), "The puzzle was solved")
    }

    @Test
    fun `unfinished submission cancellation is reported as waiting for cleanup`() = runTestWithRandomizedDispatchOrdering {
        val result = coroutinePuzzle {
            coroutineScope control@ {
                val expectation = launch { TestApis.callbackUnit.expectCall { _, callback -> callback(Unit) } }
                TestApis.bar.expectCall {
                    this@control.launch { expectation.cancelAndJoin() }
                }
            }
        }.solve {
            TestApis.callbackUnit.submitCall {
                try {
                    TestApis.bar.submitCall(Unit)
                    awaitCancellation()
                } finally {
                    importantCleanup { TestApis.nested.submitCall(Unit) }
                }
            }
        }
        val failure = result.assertIsNotOk<CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure>()
        assertEquals(listOf(TestApis.nested.id), failure.unexpectedSubmissions)
        checkNotNull(failure.expectations.single().expectedCancellationCompletionOfCallId)
        assertContains(result.renderClientMessage(), "Finish cancelling foo")
    }

    @Test
    fun `both sides can cancel the same call while its callback is running`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            val expectation = launch { TestApis.callbackUnit.expectCall { _, callback -> callback(Unit) } }
            TestApis.discovered.expectCall(Unit)
            TestApis.bar.expectCall { expectation.cancel() }
            expectation.join()
        }.solve {
            val call = launch {
                TestApis.callbackUnit.submitCall {
                    TestApis.discovered.submitCall(Unit)
                    awaitCancellation()
                }
            }
            TestApis.bar.submitCall(Unit)
            call.cancelAndJoin()
        }.assertIsOk()
    }

    @Test
    fun `expectation cancellation never targets a later call to the same endpoint`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            val first = launch { expectIntArgument(1) }
            TestApis.bar.expectCall { first.cancel() }
            first.join()
            expectIntArgument(2)
        }.solve {
            launch {
                try { TestApis.intInt.submitCall(1) } catch (_: CancellationAcrossRpc) { }
                assertEquals(12, TestApis.intInt.submitCall(2))
            }
            TestApis.bar.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `cancelling an expectation cancels its callback and waits for child cleanup`() = runTestWithRandomizedDispatchOrdering {
        var cleanedUp = false
        coroutinePuzzle {
            coroutineScope control@ {
                val expectation = launch { TestApis.callbackUnit.expectCall { _, callback -> callback(Unit) } }
                TestApis.bar.expectCanceledCall {
                    this@control.launch {
                        expectation.cancelAndJoin()
                        assertEquals(true, cleanedUp)
                    }
                    expectCancellation()
                }
                TestApis.nested.expectCall(Unit)
                TestApis.discovered.expectCall(Unit)
            }
        }.solve {
            assertThrows<CancellationAcrossRpc> {
                TestApis.callbackUnit.submitCall {
                    launch {
                        try {
                            TestApis.bar.submitCall(Unit)
                        } finally {
                            importantCleanup { TestApis.nested.submitCall(Unit) }
                            cleanedUp = true
                        }
                    }
                }
            }
            TestApis.discovered.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `cancelling an expectation before its answer cancels only its matching submission`() = runTestWithRandomizedDispatchOrdering {
        var producerCleanedUp = false
        coroutinePuzzle {
            coroutineScope control@ {
                val expectation = launch {
                    TestApis.callbackUnit.expectCall { _, _ ->
                        try {
                            TestApis.bar.expectCall(Unit)
                            awaitCancellation()
                        } finally {
                            producerCleanedUp = true
                        }
                    }
                }
                TestApis.discovered.expectCall {
                    this@control.launch {
                        expectation.cancelAndJoin()
                        assertEquals(true, producerCleanedUp)
                    }
                }
                TestApis.nested.expectCall(Unit)
            }
        }.solve {
            launch {
                assertThrows<CancellationAcrossRpc> {
                    TestApis.callbackUnit.submitCall { error("The callback must not run") }
                }
                TestApis.nested.submitCall(Unit)
            }
            TestApis.bar.submitCall(Unit)
            TestApis.discovered.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `an unmatched expectation can be cancelled without cancelling another submission`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            val expectation = launch { TestApis.unit.expectCall(Unit) }
            awaitQuiescenceAndVerifyUnmatchedSubmissions(TestApis.bar)
            expectation.cancelAndJoin()
            TestApis.bar.expectCall(Unit)
        }.solve {
            TestApis.bar.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `cancelling an expectation before registration leaves no pending call`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            repeat(3) {
                // Enter expectCall, but cancel before its producer necessarily publishes the registration.
                val expectation = launch(start = CoroutineStart.UNDISPATCHED) { TestApis.unit.expectCall(Unit) }
                expectation.cancelAndJoin()
                TestApis.bar.expectCall(Unit)
            }
        }.solve {
            repeat(3) { TestApis.bar.submitCall(Unit) }
        }.assertIsOk()
    }

    @Test
    fun `cancelling one of identical expectations leaves the other call usable`() = runTestWithRandomizedDispatchOrdering {
        val results = mutableListOf<Int?>()
        coroutinePuzzle {
            coroutineScope control@ {
                val first = launch {
                    TestApis.intInt.expectCall {
                        TestApis.bar.expectCall(Unit)
                        expectCancellation()
                    }
                }
                TestApis.intInt.expectCall { argument ->
                    TestApis.unit.expectCall {
                        this@control.launch { first.cancelAndJoin() }
                    }
                    TestApis.nested.expectCall(Unit)
                    argument + 10
                }
            }
        }.solve {
            repeat(2) {
                launch {
                    results += try { TestApis.intInt.submitCall(1) } catch (_: CancellationAcrossRpc) { null }
                }
            }
            TestApis.bar.submitCall(Unit)
            TestApis.unit.submitCall(Unit)
            TestApis.nested.submitCall(Unit)
        }.assertIsOk()
        assertEquals(listOf(null, 11), results.sortedWith(nullsFirst()))
    }

    @Test
    fun `expectation cancellation racing an answer or consumption leaves no orphaned call`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            val expectation = launch { TestApis.unit.expectCall(Unit) }
            TestApis.bar.expectCall { expectation.cancel() }
            expectation.join()
        }.solve {
            launch {
                try { TestApis.unit.submitCall(Unit) } catch (_: CancellationAcrossRpc) { }
            }
            TestApis.bar.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `exact argument expectations select their caller before wildcard expectations`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            assertEquals(2, expectIntArgument(2))
            coroutineScope {
                launch { assertEquals(3, expectIntArgument(3)) }
                assertEquals(1, TestApis.intInt.expectCall { it + 10 })
            }
        }.solve {
            (1..3).forEach { value ->
                launch { assertEquals(value + 10, TestApis.intInt.submitCall(value)) }
            }
        }.assertIsOk()
    }

    @Test
    fun `higher order submission waits for nested calls and callback children`() = runTestWithRandomizedDispatchOrdering {
        var childFinished = false
        coroutinePuzzle {
            coroutineScope {
                val expectation = launch {
                    TestApis.callbackInt.expectCall { argument, callback ->
                        assertEquals(5, argument)
                        assertEquals("callback result", callback(argument + 1))
                        assertEquals(true, childFinished)
                        "outer result"
                    }
                }
                assertEquals(listOf(TestApis.bar), awaitQuiescenceAndGetUnmatchedSubmissions().map { it.endPoint })
                TestApis.bar.expectCall { assertEquals(false, expectation.isCompleted) }
            }
        }.solve {
            val result = TestApis.callbackInt.submitCall(5) { response ->
                assertEquals(6, response)
                launch {
                    TestApis.bar.submitCall(Unit)
                    childFinished = true
                }
                "callback result"
            }
            assertEquals("outer result", result)
        }.assertIsOk()
    }

    @Test
    fun `unit argument higher order calls can be repeated in a loop`() = runTestWithRandomizedDispatchOrdering {
        var handled = 0
        coroutinePuzzle {
            repeat(3) { index ->
                TestApis.callbackUnit.expectCall { _, callback -> callback(Unit) }
                assertEquals(index + 1, handled)
            }
        }.solve {
            repeat(3) { TestApis.callbackUnit.submitCall { handled++ } }
        }.assertIsOk()
    }

    @Test
    fun `only ExceptionAcrossRpc exposes a callback failure message across RPC`() = runTestWithRandomizedDispatchOrdering {
        for ((original, expectedMessage) in listOf(
            ExceptionAcrossRpc("safe callback failure") to "safe callback failure",
            IllegalStateException("sensitive callback failure") to "Call failed",
        )) {
            coroutinePuzzle {
                val failure = assertThrows<ExceptionAcrossRpc> { TestApis.callbackUnit.expectCall { _, callback -> callback(Unit) } }
                assertEquals(expectedMessage, failure.message)
            }.solve {
                assertThrows<Exception> {
                    TestApis.callbackUnit.submitCall { throw original }
                }
            }.assertIsOk()
        }
    }

    @Test
    fun `producer failure does not invoke the callback`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            TestApis.callbackUnit.expectThrowingCall("Producer failed")
        }.solve {
            val failure = assertThrows<ExceptionAcrossRpc> {
                TestApis.callbackUnit.submitCall { error("The callback must not run") }
            }
            assertEquals("Producer failed", failure.message)
        }.assertIsOk()
    }

    @Test
    fun `callback cancellation finishes cleanup before the expectation resumes`() = runTestWithRandomizedDispatchOrdering {
        var cleanedUp = false
        coroutinePuzzle {
            coroutineScope {
                launch {
                    TestApis.callbackUnit.expectCanceledCall { _, callback -> callback(Unit) }
                    assertEquals(true, cleanedUp)
                }
                TestApis.bar.expectCanceledCall {
                    TestApis.discovered.expectCall(Unit)
                    expectCancellation()
                }
                TestApis.nested.expectCall(Unit)
            }
        }.solve {
            coroutineScope {
                val call = launch {
                    TestApis.callbackUnit.submitCall {
                        try {
                            TestApis.bar.submitCall(Unit)
                        } finally {
                            importantCleanup { TestApis.nested.submitCall(Unit) }
                            cleanedUp = true
                        }
                    }
                }
                TestApis.discovered.submitCall(Unit)
                call.cancelAndJoin()
            }
        }.assertIsOk()
    }

    @Test
    fun `cancellation racing a higher order response completes the same call`() = runTestWithRandomizedDispatchOrdering {
        val result = coroutinePuzzle {
            coroutineScope {
                launch { TestApis.callbackUnit.expectCanceledCall { _, callback -> callback(Unit) } }
                TestApis.bar.expectCall(Unit)
            }
        }.solve {
            coroutineScope {
                val call = launch {
                    TestApis.callbackUnit.submitCall {
                        awaitCancellation()
                    }
                }
                TestApis.bar.submitCall(Unit)
                call.cancelAndJoin()
            }
        }
        result.assertIsOk()
        val submissions = result.history.filterIsInstance<CoroutinePuzzleHistoryBatch.Submission>().flatMap { it.entries }
        val callId = submissions.single {
            (it.payload as? CoroutinePuzzleCallPayload.Submission)?.endPoint == TestApis.callbackUnit.id
        }.callId
        val lifecycle = submissions.filter { it.callId == callId && it.payload !is CoroutinePuzzleCallPayload.Submission }
        // Calls and callbacks share cancellation requests and terminal outcomes.
        assertEquals(listOf(CoroutinePuzzleCallPayload.Cancel), lifecycle.map { it.payload })
    }

    @Test
    fun `collector expectation scope stops without expected collectors`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { }
        }.solve { }.assertIsOk()
    }

    @Test
    fun `creating a cold flow does not submit a collector`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        coroutinePuzzle { }.solve {
            endpoint.asFlow()
        }.assertIsOk()
    }

    @Test
    fun `cancelling an idle higher order flow call cancels collection`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            val collection = launch {
                TestApis.flowUnitInt.expectCall { _, _ -> expectCancellation() }
            }
            awaitQuiescenceAndVerifyUnmatchedSubmissions(TestApis.flowUnitInt)
            collection.cancelAndJoin()
            TestApis.bar.expectCall(Unit)
        }.solve {
            assertThrows<CancellationAcrossRpc> { TestApis.flowUnitInt.asFlow().collect() }
            TestApis.bar.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `expecting collector use waits before entering its body`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        val bodyEntered = CompletableDeferred<Unit>()
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                collectors.use {
                    bodyEntered.complete(Unit)
                }
            }
        }.solve {
            assertEquals(false, bodyEntered.isCompleted)
            endpoint.asFlow().let { it.toList() }
            bodyEntered.await()
        }.assertIsOk()
    }

    @Test
    fun `collector matched flow emits values then completes`() = runTestWithRandomizedDispatchOrdering {
        val collectorEndpoint = TestApis.flowStringInt
        coroutinePuzzle {
            collectorEndpoint.expectingFlowCollector().use { collectors ->
                collectors.use { (argument, emit) ->
                    assertEquals("initial value", argument)
                    emit(3)
                    emit(5)
                }
            }
        }.solve {
            val values = collectorEndpoint.asFlow("initial value").let { flow ->
                flow.toList()
            }
            assertEquals(listOf(3, 5), values)
        }.assertIsOk()
    }

    @Test
    fun `emitting a flow value waits until its collector finishes handling it`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) ->
                    coroutineScope {
                        val emission = launch { emit(7) }
                        TestApis.bar.expectCall {
                            assertEquals(false, emission.isCompleted)
                        }
                        emission.join()
                    }
                }
            }
        }.solve {
            endpoint.asFlow().let { flow ->
                flow.collect { TestApis.bar.submitCall(Unit) }
            }
        }.assertIsOk()
    }

    @Test
    fun `quiescence identifies a real flow waiting for its next value`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowStringInt
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) ->
                    emit(7)
                    val submission = awaitQuiescenceAndGetUnmatchedSubmissions().single()
                    assertEquals(endpoint, submission.endPoint)
                    val argument = submission.argument.assertIs<Pair<*, *>>()
                    assertEquals("requested stream", argument.first)
                    emit(8)
                    assertEquals(submission, awaitQuiescenceAndGetUnmatchedSubmissions().single())
                }
            }
        }.solve {
            endpoint.asFlow("requested stream").let { flow ->
                assertEquals(listOf(7, 8), flow.toList())
            }
        }.assertIsOk()
    }

    @Test
    fun `cancelled unmatched flow is no longer reported as waiting`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowStringInt
        coroutinePuzzle {
            val waiting = awaitQuiescenceAndGetUnmatchedSubmissions()
            assertEquals(1, waiting.count { it.endPoint == endpoint })
            TestApis.bar.expectCall(Unit)
            TestApis.bar.expectCall(Unit)
            assertEquals(emptyList(), awaitQuiescenceAndGetUnmatchedSubmissions())
        }.solve {
            val collection = launch { endpoint.asFlow("waiting stream").let { it.toList() } }
            TestApis.bar.submitCall(Unit)
            collection.cancelAndJoin()
            TestApis.bar.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `quiescence distinguishes idle collectors of the same flow`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emitFirst) ->
                    collectors.use { (_, emitSecond) ->
                        emitFirst(1)
                        val first = awaitQuiescenceAndGetUnmatchedSubmissions()
                        assertEquals(listOf(endpoint, endpoint), first.map { it.endPoint })
                        emitSecond(2)
                        val both = awaitQuiescenceAndGetUnmatchedSubmissions()
                        assertEquals(listOf(endpoint, endpoint), both.map { it.endPoint })
                        assertEquals(2, both.map { it.argument }.distinct().size)
                        assertEquals(first.toSet(), both.toSet())
                        emitSecond(4)
                        assertEquals(both.toSet(), awaitQuiescenceAndGetUnmatchedSubmissions().toSet())
                        emitFirst(3)
                        assertEquals(both.toSet(), awaitQuiescenceAndGetUnmatchedSubmissions().toSet())
                    }
                }
            }
        }.solve {
            withImportantCleanup {
                endpoint.asFlow().let { flow ->
                    val collected = coroutineScope {
                        listOf(async { flow.toList() }, async { flow.toList() }).awaitAll()
                    }
                    assertEquals(listOf(listOf(1, 3), listOf(2, 4)), collected.sortedBy { it.first() })
                }
            }
        }.assertIsOk()
    }

    @Test
    fun `cancelling an unmatched ordinary call removes it from quiescence`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            assertEquals(
                setOf(TestApis.discovered, TestApis.bar),
                awaitQuiescenceAndGetUnmatchedSubmissions().map { it.endPoint }.toSet(),
            )
            TestApis.bar.expectCall(Unit)
            TestApis.bar.expectCall(Unit)
            assertEquals(emptyList(), awaitQuiescenceAndGetUnmatchedSubmissions())
        }.solve {
            val waiting = launch { TestApis.discovered.submitCall(Unit) }
            TestApis.bar.submitCall(Unit)
            waiting.cancelAndJoin()
            TestApis.bar.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `blocked flow delivery is an expected action and shuts down cleanly`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        val result = coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) -> emit(7) }
            }
        }.solve {
            endpoint.asFlow().let { flow -> flow.collect { awaitCancellation() } }
        }
        val failure = result.assertIsNotOk<CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure>()
        assertEquals(emptyList(), failure.unexpectedSubmissions)
        assertEquals(endpoint.id, failure.expectations.single().endPoint)
        checkNotNull(failure.expectations.single().expectedCallbackCompletionOfCallId)
        assertContains(result.renderClientMessage(), "Finish the callback from numbers()")
    }

    @Test
    fun `take finishes its last delivery before stopping collection`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                val collector = launch {
                    collectors.use { (_, emit) ->
                        emit(7)
                        TestApis.bar.expectCall(Unit)
                        awaitCancellation()
                    }
                }
                TestApis.unit.expectCall(Unit)
                collector.cancelAndJoin()
            }
        }.solve {
            endpoint.asFlow().let { flow ->
                assertEquals(listOf(7), flow.take(1).toList())
                TestApis.bar.submitCall(Unit)
                TestApis.unit.submitCall(Unit)
            }
        }.assertIsOk()
    }

    @Test
    fun `take cannot satisfy another emission after stopping collection`() = runTestWithRandomizedDispatchOrdering {
        val result = coroutinePuzzle {
            TestApis.flowUnitInt.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) ->
                    emit(7)
                    emit(8)
                }
            }
        }.solve {
            TestApis.flowUnitInt.asFlow().let { flow ->
                assertEquals(listOf(7), flow.take(1).toList())
            }
        }
        val failure = result.assertIsNotOk<CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure>()
        assertEquals(listOf(TestApis.flowUnitInt.id), failure.expectedFollowups.map { it.endPoint })
    }

    @Test
    fun `collector cancellation finishes delivery after downstream cleanup`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                val collector = launch {
                    collectors.use { (_, emit) ->
                        coroutineScope {
                            val emission = launch { emit(7) }
                            TestApis.bar.expectCanceledCall {
                                TestApis.unit.expectCall(Unit)
                                expectCancellation()
                            }
                            emission.join()
                        }
                        TestApis.unit.expectCall(Unit)
                        awaitCancellation()
                    }
                }
                TestApis.discovered.expectCall(Unit)
                collector.cancelAndJoin()
            }
        }.solve {
            endpoint.asFlow().let { flow ->
                val collection = launch { flow.collect { TestApis.bar.submitCall(Unit) } }
                TestApis.unit.submitCall(Unit)
                collection.cancelAndJoin()
                TestApis.unit.submitCall(Unit)
                TestApis.discovered.submitCall(Unit)
            }
        }.assertIsOk()
    }

    @Test
    fun `downstream failure finishes delivery and keeps the original exception`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        val failure = IllegalStateException("Downstream failed")
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                val collector = launch {
                    collectors.use { (_, emit) ->
                        emit(7)
                        TestApis.bar.expectCall(Unit)
                        awaitCancellation()
                    }
                }
                TestApis.unit.expectCall(Unit)
                collector.cancelAndJoin()
            }
        }.solve {
            endpoint.asFlow().let { flow ->
                val result = runCatching { flow.collect { throw failure } }
                check(generateSequence(result.exceptionOrNull()) { it.cause }.any { it === failure })
                TestApis.bar.submitCall(Unit)
                TestApis.unit.submitCall(Unit)
            }
        }.assertIsOk()
    }

    @Test
    fun `cancellation racing a flow response terminates without waiting for a nonexistent producer`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        val metadata = serverMetadataOf(TestApis) {
            endpoint.register(flowFunctionCall = "numbers()")
        }
        coroutinePuzzle(metadata) {
            launch { TestApis.bar.expectCall(Unit) }
            endpoint.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) -> emit(7) }
            }
        }.solve {
            endpoint.asFlow().let { flow ->
                val collection = launch { flow.collect() }
                TestApis.bar.submitCall(Unit)
                collection.cancelAndJoin()
            }
        }
    }

    @Test
    fun `separate flow resources for one endpoint keep independent collector identities`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        coroutinePuzzle {
            val waiting = awaitQuiescenceAndGetUnmatchedSubmissions()
            assertEquals(listOf(endpoint, endpoint), waiting.map { it.endPoint })
            assertEquals(2, waiting.map { it.argument }.distinct().size)
            endpoint.expectingFlowCollector().use { collectors ->
                coroutineScope {
                    launch { collectors.use { (_, emit) -> repeat(3) { emit(10 + it) } } }
                    launch { collectors.use { (_, emit) -> repeat(3) { emit(20 + it) } } }
                }
            }
        }.solve {
            val collected = coroutineScope {
                listOf(
                    async { endpoint.asFlow().let { it.toList() } },
                    async { endpoint.asFlow().let { it.toList() } },
                ).awaitAll()
            }
            assertEquals(listOf(listOf(10, 11, 12), listOf(20, 21, 22)), collected.sortedBy { it.first() })
        }.assertIsOk()
    }

    @Test
    fun `cancelling collector registration withdraws its initial expectation`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            TestApis.flowUnitInt.expectingFlowCollector().use { collectors ->
                val registration = launch { collectors.use { error("No collector was submitted") } }
                awaitQuiescenceAndVerifyUnmatchedSubmissions(TestApis.bar)
                registration.cancelAndJoin()
                TestApis.bar.expectCall(Unit)
            }
        }.solve {
            TestApis.bar.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `cancelling a pending emission withdraws its expectation without losing the next value`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) ->
                    coroutineScope emissions@ {
                        val first = launch { emit(1) }
                        TestApis.bar.expectCall {
                            val pending = this@emissions.launch { emit(2) }
                            awaitQuiescenceAndVerifyUnmatchedSubmissions()
                            pending.cancelAndJoin()
                        }
                        first.join()
                        emit(3)
                    }
                }
            }
        }.solve {
            endpoint.asFlow().let { flow ->
                val values = mutableListOf<Int>()
                flow.collect { value ->
                    values += value
                    if (value == 1) TestApis.bar.submitCall(Unit)
                }
                assertEquals(listOf(1, 3), values)
            }
        }.assertIsOk()
    }

    @Test
    fun `cancelling an emission waits for its downstream cancellation cleanup`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        var cleanedUp = false
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                val collector = launch {
                    collectors.use { (_, emit) ->
                        coroutineScope emissions@ {
                            val emission = launch { emit(7) }
                            TestApis.bar.expectCanceledCall {
                                this@emissions.launch {
                                    emission.cancelAndJoin()
                                    assertEquals(true, cleanedUp)
                                }
                                expectCancellation()
                            }
                            TestApis.nested.expectCall(Unit)
                            TestApis.discovered.expectCall(Unit)
                        }
                        awaitCancellation()
                    }
                }
                TestApis.unit.expectCall(Unit)
                collector.cancelAndJoin()
            }
        }.solve {
            endpoint.asFlow().let { flow ->
                assertThrows<CancellationAcrossRpc> {
                    flow.collect {
                        try {
                            TestApis.bar.submitCall(Unit)
                        } finally {
                            importantCleanup { TestApis.nested.submitCall(Unit) }
                            cleanedUp = true
                        }
                    }
                }
            }
            TestApis.discovered.submitCall(Unit)
            TestApis.unit.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `concurrent collectors receive only their addressed emissions`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                coroutineScope {
                    launch { collectors.use { (_, emit) -> repeat(3) { emit(10 + it) } } }
                    launch { collectors.use { (_, emit) -> repeat(3) { emit(20 + it) } } }
                }
            }
        }.solve {
            endpoint.asFlow().let { flows ->
                val collected = coroutineScope {
                    listOf(
                        async { flows.toList() },
                        async { flows.toList() },
                    ).awaitAll()
                }
                assertEquals(listOf(listOf(10, 11, 12), listOf(20, 21, 22)), collected.sortedBy { it.first() })
            }
        }.assertIsOk()
    }

    @Test
    fun `submitting more flow collectors than expected reports the collector endpoint`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        val result = coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                collectors.use { }
            }
        }.solve {
            endpoint.asFlow().let { flows ->
                coroutineScope {
                    launch { flows.toList() }
                    launch { flows.toList() }
                }
            }
        }

        result.assertIsNotOk<CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure>()
        assertContains(result.renderClientMessage(), CoroutinePuzzleFlowErrorMessages.tooManyCollectors("numbers()"))
    }

    @Test
    fun `submitting fewer flow collectors than expected reports the collector endpoint`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        val result = coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                coroutineScope {
                    launch { collectors.use { } }
                    launch { collectors.use { } }
                }
            }
        }.solve {
            endpoint.asFlow().let { it.toList() }
        }

        result.assertIsNotOk<CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure>()
        assertContains(result.renderClientMessage(), CoroutinePuzzleFlowErrorMessages.tooFewCollectors("numbers()"))
    }

    @Test
    fun `collecting a completed flow again requires another expected collector`() = runTestWithRandomizedDispatchOrdering {
        val result = coroutinePuzzle {
            TestApis.flowUnitInt.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) -> emit(7) }
            }
        }.solve {
            TestApis.flowUnitInt.asFlow().let { flow ->
                assertEquals(listOf(7), flow.toList())
                flow.toList()
            }
        }
        result.assertIsNotOk<CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure>()
        assertContains(result.renderClientMessage(), CoroutinePuzzleFlowErrorMessages.tooManyCollectors("numbers()"))
    }

    @Test
    fun `internal calls are NOT shown in history of error message`() = runTestWithRandomizedDispatchOrdering {
        val publicEndpoint = TestApis.unit
        coroutinePuzzle {
            TestApis.callLifetime.expectCall(Unit)
        }.solve {
            TestApis.callLifetime.submitCall(Unit)
            publicEndpoint.submitCall(Unit) // Should result in error
        }
            .assertIsNotOk()
            .renderClientMessage()
            .assert({ "lifetime" !in it.lowercase() }) { "Message must not mention internal endpoint" }
    }

    @Test
    fun `empty puzzle works with empty solution`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle { }.solve { }.assertIsOk()
    }

    @Test
    fun `internal calls ARE shown in expected calls part of error message`() = runTestWithRandomizedDispatchOrdering {
        val publicEndpoint = TestApis.unit
        coroutinePuzzle {
            publicEndpoint.expectCall(Unit)
        }.solve {
            TestApis.callLifetime.submitCall(Unit)
        }
            .assertIsNotOk()
            .renderClientMessage()
            .assert({ "lifetime" in it.lowercase() }) { "Message must mention internal endpoint" }
    }

    class ExceptionForTestBelow : Exception("Test exception")

    @Test
    fun `error that happens in expect call is thrown into submit call`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.unit
        coroutinePuzzle {
            assertThrows<ExceptionForTestBelow> {
                endpoint.expectCall { throw ExceptionForTestBelow() }
            }
        }.solve {
            assertEquals("Call failed", assertThrows<ExceptionAcrossRpc> {
                endpoint.submitCall(Unit)
            }.message)
        }
    }

    @Test
    fun `nothing hangs when submit call gets canceled`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.unit
        val endpointIsRunning = TestApis.bar
        coroutinePuzzle {
            endpoint.expectCall {
                endpointIsRunning.expectCall(Unit)
            }
        }.solve {
            launch {
                endpoint.submitCall(Unit)
            }.sideEffect {
                endpointIsRunning.submitCall(Unit)
                it.cancelAndJoin()
            }
        }
    }

    @Test
    fun `expectCanceledCall of matching submit call does NOT throw into coroutine puzzle scope`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.intInt
        val endpointIsRunning = TestApis.bar
        coroutinePuzzle {
            endpoint.expectCanceledCall {
                endpointIsRunning.expectCall(Unit)
                throw assertThrows<CancellationAcrossRpc> {
                    expectCancellation()
                }
            }
        }.solve {
            launch {
                endpoint.submitCall(5)
            }.sideEffect {
                endpointIsRunning.submitCall(Unit)
                it.cancel()
            }
        }.assertIsOk()
    }

    @Test
    fun `expected cancellation is reported when another request is unexpected`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.intInt
        val endpointIsRunning = TestApis.bar
        val result = coroutinePuzzle {
            endpoint.expectCanceledCall {
                endpointIsRunning.expectCall(Unit)
                expectCancellation()
            }
        }.solve {
            launch { endpoint.submitCall(5) }.sideEffect {
                endpointIsRunning.submitCall(Unit)
                TestApis.unit.submitCall(Unit)
            }
        }

        val expectedCallId = result.history
            .filterIsInstance<CoroutinePuzzleHistoryBatch.Submission>()
            .flatMap { it.entries }
            .single {
                (it.payload as? CoroutinePuzzleCallPayload.Submission)?.endPoint == endpoint.id
            }
            .callId
        val failure = result.assertIsNotOk<CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure>()
        assertEquals(listOf(TestApis.unit.id), failure.unexpectedSubmissions)
        assertEquals(
            CoroutinePuzzleExpectedFollowup(
                endPoint = endpoint.id,
                expectedCancellationOfCallId = expectedCallId,
            ),
            failure.expectations.single(),
        )
    }

    @Test
    fun `expected cancellation of a flow request is reported as a flow action`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        val endpointIsRunning = TestApis.bar
        val result = coroutinePuzzle {
            endpoint.expectCanceledCall { _, _ ->
                endpointIsRunning.expectCall(Unit)
                expectCancellation()
            }
        }.solve {
            launch { endpoint.submitCall(Unit) { } }.sideEffect {
                endpointIsRunning.submitCall(Unit)
                TestApis.unit.submitCall(Unit)
            }
        }

        val expectedCallId = result.history
            .filterIsInstance<CoroutinePuzzleHistoryBatch.Submission>()
            .flatMap { it.entries }
            .single {
                (it.payload as? CoroutinePuzzleCallPayload.Submission)?.endPoint == endpoint.id
            }
            .callId
        val failure = result.assertIsNotOk<CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure>()
        assertEquals(
            CoroutinePuzzleExpectedFollowup(
                endPoint = endpoint.id,
                expectedCancellationOfCallId = expectedCallId,
            ),
            failure.expectations.single(),
        )
        assertEquals(
            "Currently the expected action is Cancel numbers().\n" +
                "But instead, your solution made this request: foo.",
            failure.renderClientMessage(),
        )
    }

    @Test
    fun `regular expectCall cancellation of matching submit call DOES throw into coroutine puzzle scope`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.unit
        val endpointIsRunning = TestApis.bar
        coroutinePuzzle {
            endpoint.expectCall {
                endpointIsRunning.expectCall(Unit)
                assertThrows<CancellationAcrossRpc> {
                    awaitCancellation()
                }
            }
        }.solve {
            launch {
                endpoint.submitCall(Unit)
            }.sideEffect {
                endpointIsRunning.submitCall(Unit)
                it.cancel()
            }
        }
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint synchronously while the expectation is parallel fails`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.intString
        coroutinePuzzle {
            awaitQuiescenceAndVerifyUnmatchedSubmissions(endpoint, endpoint)
            launch { endpoint.expectCall { it.toString() } }
            endpoint.expectCall { it.toString() }
        }.solve {
            endpoint.submitCall(42)
        }
            .result
            .assertIs<CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure> { "Synchronous submission should fail an exact-parallelism expectation" }
    }

    @Test
    fun `exact concurrent check identifies only its incorrect submissions`() = runTestWithRandomizedDispatchOrdering {
        val expected = TestApis.intString
        val incorrect = TestApis.bar

        val failure = coroutinePuzzle {
            awaitQuiescenceAndVerifyUnmatchedSubmissions(expected)
        }.solve {
            incorrect.submitCall(Unit)
        }.result.assertIs<CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure>()

        assertEquals(listOf(incorrect.id), failure.incorrectSubmissions)
    }

    @Test
    fun `puzzle-specific quiescence check can identify incorrect submissions`() = runTestWithRandomizedDispatchOrdering {
        val incorrect = TestApis.bar

        val failure = coroutinePuzzle {
            val submissions = awaitQuiescenceAndGetUnmatchedSubmissions()
            fail("This call is incorrect", submissions.map { it.endPoint })
        }.solve {
            incorrect.submitCall(Unit)
        }.result.assertIs<CoroutinePuzzleSolutionResult.CustomFailure>()

        assertEquals(listOf(incorrect.id), failure.incorrectSubmissions)
    }

    @Test
    fun `failure inside collector resources leaves the incorrect call pending`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.intInt
        val flow = TestApis.flowUnitInt
        val returned = mutableListOf<Int>()
        var producerCleanedUp = false
        val states = coroutinePuzzle {
            launch {
                endpoint.expectCall { argument ->
                    assertEquals(1, argument)
                    try {
                        awaitCancellation()
                    } finally {
                        producerCleanedUp = true
                    }
                }
            }
            flow.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) ->
                    awaitQuiescenceAndVerifyUnmatchedSubmissions(flow)
                    launch { emit(2) }
                    val second = awaitQuiescenceAndGetUnmatchedSubmissions().single()
                    assertEquals(UnmatchedSubmission(endpoint, 2), second)
                    fail("Overlapping calls", listOf(second.endPoint))
                }
            }
        }.asPuzzle().solveAsFlow {
            launch { returned += endpoint.submitCall(1) }
            flow.asFlow().collect { returned += endpoint.submitCall(it) }
        }.toList()

        val completion = states.filterIsInstance<CoroutinePuzzleSolveState.Completed>().single()
        assertEquals(CoroutinePuzzleSolutionResult.CustomFailure("Overlapping calls", listOf(endpoint.id)), completion.result)
        assertEquals(completion, states.last())
        val history = states.filterIsInstance<CoroutinePuzzleSolveState.Running>().map { it.batch }
        val calls = context(testClientMetadata) { coroutineTimeline(history) }.filter { it.endpoint == endpoint.id }
        assertEquals(2, calls.size)
        val (first, second) = calls
        assertTrue(first.startBatch < second.startBatch)
        assertNull(second.endBatch, "The incorrect call must still be pending")
        assertNull(first.endBatch)
        assertEquals(second.startBatch, history.lastIndex, "History must stop at the incorrect call")
        assertEquals(emptyList(), returned)
        assertTrue(producerCleanedUp)
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint in parallel while the expectation is synchronous fails`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.intString
        coroutinePuzzle {
            awaitQuiescenceAndVerifyUnmatchedSubmissions(endpoint)
            endpoint.expectCall { it.toString() }
        }.solve {
            launch { endpoint.submitCall(42) }
            endpoint.submitCall(42)
        }
            .result
            .assertIs<CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure> { "Parallel submission should fail a synchronous exact-parallelism expectation" }
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint with double parallel while the expectation is triple parallel fails`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.intString
        coroutinePuzzle {
            awaitQuiescenceAndVerifyUnmatchedSubmissions(endpoint, endpoint, endpoint)
            launch { endpoint.expectCall { it.toString() } }
            launch { endpoint.expectCall { it.toString() } }
            endpoint.expectCall { it.toString() }
        }.solve {
            launch { endpoint.submitCall(42) }
            endpoint.submitCall(42)
        }
            .result
            .assertIs<CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure> { "Double parallel submission should fail a triple-parallel expectation" }
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint with triple parallel while the expectation is double parallel fails`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.intString
        coroutinePuzzle {
            awaitQuiescenceAndVerifyUnmatchedSubmissions(endpoint, endpoint)
            launch { endpoint.expectCall { it.toString() } }
            endpoint.expectCall { it.toString() }
        }.solve {
            launch { endpoint.submitCall(42) }
            launch { endpoint.submitCall(42) }
            endpoint.submitCall(42)
        }
            .result
            .assertIs<CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure> { "Triple parallel submission should fail a double-parallel expectation" }
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint with matching parallelism succeeds`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.intString
        coroutinePuzzle {
            awaitQuiescenceAndVerifyUnmatchedSubmissions(endpoint, endpoint, endpoint)
            endpoint.expectCall { it.toString() }
            endpoint.expectCall { it.toString() }
            endpoint.expectCall { it.toString() }
        }.solve {
            launch { endpoint.submitCall(42) }
            launch { endpoint.submitCall(42) }
            endpoint.submitCall(42)
        }.assertIsOk()
    }

    @Test
    fun `full quiescence is detected`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            awaitCancellation()
        }.solve {
            awaitCancellation()
        }
            .assertIsNotOk<CoroutinePuzzleSolutionResult.FullyQuiescent>()
    }

    @Test
    fun `quiescence is detected on solution side`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.unit
        coroutinePuzzle {
            endpoint.expectCall(Unit)
        }.solve {
            awaitCancellation()
        }.assertIsNotOk()
    }

    @Test
    fun `quiescence is detected on expectation side`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.unit
        coroutinePuzzle {
            awaitCancellation()
        }.solve {
            endpoint.submitCall(Unit)
        }.assertIsNotOk()
    }

    @Test
    fun `expectation can await quiescence and inspect unmatched submissions`() = runTestWithRandomizedDispatchOrdering {
        val alreadyExpected = TestApis.alreadyExpected
        val discovered = TestApis.discovered

        coroutinePuzzle {
            launch { alreadyExpected.expectCall(Unit) }

            assertEquals(
                listOf(UnmatchedSubmission(discovered, Unit)),
                awaitQuiescenceAndGetUnmatchedSubmissions(),
            )
            discovered.expectCall(Unit)
        }.solve {
            launch { alreadyExpected.submitCall(Unit) }
            discovered.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `quiescence inspection retains an unmatched flow endpoint and callback reference`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.flowUnitInt
        coroutinePuzzle {
            val waiting = awaitQuiescenceAndGetUnmatchedSubmissions().single()
            assertEquals(endpoint, waiting.endPoint)
            val argument = waiting.argument.assertIs<Pair<*, *>>()
            assertEquals(Unit, argument.first)
            argument.second.assertIs<CoroutinePuzzleCallbackReference<*, *>>()
            endpoint.expectCall { _, _ -> }
        }.solve {
            endpoint.submitCall(Unit) { }
        }.assertIsOk()
    }

    @Test
    fun `puzzle can finish immediately after a final quiescence check`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.unit

        coroutinePuzzle {
            endpoint.expectCall(Unit)
            awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList())
        }.solve {
            endpoint.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `puzzle can finish after lifetime-triggered cancellation without a final quiescence check`() =
        runTestWithRandomizedDispatchOrdering {
            val outerEndpoint = TestApis.outer
            val nestedEndpoint = TestApis.nested
            val cancelSolution = CompletableDeferred<Unit>()

            coroutinePuzzle {
                launch {
                    TestApis.callLifetime.expectCall {
                        cancelSolution.await()
                    }
                }
                outerEndpoint.expectCanceledCall {
                    nestedEndpoint.expectCanceledCall {
                        cancelSolution.complete(Unit)
                        awaitCancellation()
                    }
                    awaitCancellation()
                }
            }.solve {
                launch {
                    launch { outerEndpoint.submitCall(Unit) }
                    launch { nestedEndpoint.submitCall(Unit) }
                    awaitCancellation()
                }.sideEffect {
                    TestApis.callLifetime.submitCall(Unit)
                    it.cancel()
                }
            }.assertIsOk()
        }

    @Test
    fun `completion of an unmatched submission is an explicit failure`() = runTestWithRandomizedDispatchOrdering {
        val unit = Json.encodeToJsonElement(serializer<Unit>(), Unit)
        coroutinePuzzle { awaitCancellation() }.use { (expectations, submissions) ->
            submissions.send(listOf(
                WithCallId(1, CoroutinePuzzleCallPayload.CallSubmitted(TestApis.unit.id, unit)),
                WithCallId(1, Outcome.Cancelled),
            ))
            expectations.receive().assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Completion>()
                .result.assertIs<CoroutinePuzzleSolutionResult.CustomFailure>()
                .message.assertEquals("Unexpected completion for call 1: its expectation was not awaiting completion.")
        }
    }

    @Test
    fun `cancellation after result consumption is an explicit failure`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.unit

        coroutinePuzzle {
            endpoint.expectCall(Unit)
            TestApis.bar.expectCall(Unit)
            awaitCancellation()
        }.use { (expectations, submissions) ->
            submissions.send(listOf(
                WithCallId(
                    callId = 1,
                    payload = CoroutinePuzzleCallPayload.CallSubmitted(
                        endpoint.id,
                        Json.encodeToJsonElement(serializer<Unit>(), Unit),
                    ),
                ),
            ))
            expectations.receive().assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Batch>()

            submissions.send(listOf(
                WithCallId(2, CoroutinePuzzleCallPayload.CallSubmitted(TestApis.bar.id, Json.encodeToJsonElement(serializer<Unit>(), Unit))),
            ))
            expectations.receive().assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Batch>()

            submissions.send(listOf(
                WithCallId(
                    callId = 1,
                    payload = CoroutinePuzzleCallPayload.Cancel,
                ),
            ))
            expectations.receive()
                .assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Completion>()
                .result
                .assertIs<CoroutinePuzzleSolutionResult.CustomFailure>()
                .message
                .assertEquals("Unexpected cancellation for call 1: its expectation was not running.")
        }
    }

    @Test
    fun `cancellation crossing a returned answer replays that answer`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.unit

        coroutinePuzzle {
            endpoint.expectCall(Unit)
        }.use { (expectations, submissions) ->
            submissions.send(listOf(
                WithCallId(
                    callId = 1,
                    payload = CoroutinePuzzleCallPayload.CallSubmitted(
                        endpoint.id,
                        Json.encodeToJsonElement(serializer<Unit>(), Unit),
                    ),
                ),
            ))
            expectations.receive().assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Batch>()

            submissions.send(listOf(
                WithCallId(
                    callId = 1,
                    payload = CoroutinePuzzleCallPayload.Cancel,
                ),
            ))
            expectations.receive()
                .assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Batch>()
                .batch.assertEquals(listOf(WithCallId(1, Outcome.Returned(Json.encodeToJsonElement(serializer<Unit>(), Unit)))))
            submissions.close()
            expectations.receive()
                .assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Completion>()
                .result
                .assertIs<CoroutinePuzzleSolutionResult.Success>()
        }
    }

    @Test
    fun `failure teardown is not reported as an unexpected cancellation`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = TestApis.unit

        coroutinePuzzle {
            fail("intended failure")
        }.solve {
            endpoint.submitCall(Unit)
        }
            .result
            .assertIs<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals("intended failure")
    }

    @Test
    fun `verify works`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            verify(false) { "AAAH" }
        }.solve {
        }
            .assertIsNotOk()
            .assertIs<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals("AAAH")
        coroutinePuzzle {
            verify(true) { "AAAH" }
        }.solve {
        }
            .assertIsOk()
    }

    @Test
    fun `fail works`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle {
            fail("AAAH")
        }.solve {
        }
            .assertIsNotOk()
            .assertIs<CoroutinePuzzleSolutionResult.CustomFailure>()
            .message
            .assertEquals("AAAH")
    }
}


internal fun <T> Any?.assertEquals(other: T): T {
    assertEquals(other, this)
    return other
}

internal fun ResultsWHistory.assertIsOk(): Unit = when (result) {
    CoroutinePuzzleSolutionResult.Success -> { /** All OK! */ }
    else -> junitFail { renderClientMessage() }
}

internal fun ResultsWHistory.assertIsNotOk(): CoroutinePuzzleSolutionResult = result.also {
    assert(it !is CoroutinePuzzleSolutionResult.Success) { "Puzzle succeeded unexpectedly \n${renderClientMessage()}" }
}

@JvmName("assertIsNotOkGeneric")
internal inline fun <reified T: CoroutinePuzzleSolutionResult> ResultsWHistory.assertIsNotOk(): T =
    assertIsNotOk().assertIs<T> { "Expected ${T::class.simpleName} but got ${it!!::class.simpleName}\n${renderClientMessage()}" }

internal inline fun <reified T> Any?.assertIs(
    message: (Any?) -> String = { "Expected instance of ${T::class}, but got $it" },
): T = if (this is T) this else junitFail(message(this))

internal inline fun Any?.assertIs(other: Any?, message: (Any?) -> String) {
    assertEquals(this, message(this))
}

internal inline fun <T> T.assert(test: (T) -> Boolean, message: (T) -> String): T =
    this.also { if (!test(this)) kotlin.test.fail(message(this) + "\nActual value was: $this") }
