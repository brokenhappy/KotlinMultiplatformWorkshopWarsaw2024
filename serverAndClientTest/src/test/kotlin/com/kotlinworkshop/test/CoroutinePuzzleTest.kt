package com.kotlinworkshop.test

import kmpworkshop.client.toMessage
import kmpworkshop.client.asFlows
import kmpworkshop.client.CoroutinePuzzleFlowErrorMessages
import kmpworkshop.common.*
import kmpworkshop.server.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kmpworkshop.common.CoroutinePuzzleResultWithHistory as ResultsWHistory
import org.junit.jupiter.api.fail as junitFail

class CoroutinePuzzleTest {
    @Test
    fun `collector expectation scope stops without expected collectors`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<WithCallId<Unit>, WithCallId<ValueOrCompletion<Int>>>("unused collector expectation")
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { }
        }.solve { }.assertIsOk()
    }

    @Test
    fun `collector flow resource stops when used without collectors`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<WithCallId<Unit>, WithCallId<ValueOrCompletion<Int>>>("unused collector flow")
        coroutinePuzzle { }.solve {
            endpoint.asFlows().use { }
        }.assertIsOk()
    }

    @Test
    fun `expecting collector use waits before entering its body`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<WithCallId<Unit>, WithCallId<ValueOrCompletion<Int>>>("waiting collector")
        val bodyEntered = CompletableDeferred<Unit>()
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                collectors.use {
                    bodyEntered.complete(Unit)
                }
            }
        }.solve {
            assertEquals(false, bodyEntered.isCompleted)
            endpoint.asFlows().use { it.toList() }
            bodyEntered.await()
        }.assertIsOk()
    }

    @Test
    fun `collector matched flow emits values then completes`() = runTestWithRandomizedDispatchOrdering {
        val collectorEndpoint = coroutinePuzzleEndPoint<WithCallId<String>, WithCallId<ValueOrCompletion<Int>>>("next value")
        coroutinePuzzle {
            collectorEndpoint.expectingFlowCollector().use { collectors ->
                collectors.use { (argument, emit) ->
                    assertEquals("initial value", argument)
                    emit(3)
                    emit(5)
                }
            }
        }.solve {
            val values = collectorEndpoint.asFlows("initial value").use { flow ->
                flow.toList()
            }
            assertEquals(listOf(3, 5), values)
        }.assertIsOk()
    }

    @Test
    fun `concurrent collectors receive only their addressed emissions`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<WithCallId<Unit>, WithCallId<ValueOrCompletion<Int>>>("next concurrent value")
        coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                coroutineScope {
                    launch { collectors.use { (_, emit) -> repeat(3) { emit(10 + it) } } }
                    launch { collectors.use { (_, emit) -> repeat(3) { emit(20 + it) } } }
                }
            }
        }.solve {
            endpoint.asFlows().use { flows ->
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
        val endpoint = emitNumber
        val result = coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                collectors.use { }
            }
        }.solve {
            endpoint.asFlows().use { flows ->
                coroutineScope {
                    launch { flows.toList() }
                    launch { flows.toList() }
                }
            }
        }

        result.assertIsNotOk<CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure>()
        assertContains(result.toMessage(), CoroutinePuzzleFlowErrorMessages.tooManyCollectors("the numbers Flow"))
    }

    @Test
    fun `submitting fewer flow collectors than expected reports the collector endpoint`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = emitNumber
        val result = coroutinePuzzle {
            endpoint.expectingFlowCollector().use { collectors ->
                coroutineScope {
                    launch { collectors.use { } }
                    launch { collectors.use { } }
                }
            }
        }.solve {
            endpoint.asFlows().use { it.toList() }
        }

        result.assertIsNotOk<CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure>()
        assertContains(result.toMessage(), CoroutinePuzzleFlowErrorMessages.tooFewCollectors("the numbers Flow"))
    }

    @Test
    fun `requesting more flow values than expected reports the completed flow`() = runTestWithRandomizedDispatchOrdering {
        val result = coroutinePuzzle {
            emitNumber.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) -> emit(7) }
            }
        }.solve {
            emitNumber.submitCall(WithCallId(1, Unit))
            emitNumber.submitCall(WithCallId(1, Unit))
            emitNumber.submitCall(WithCallId(1, Unit))
        }

        result.assertIsNotOk<CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure>()
        assertContains(
            result.toMessage(),
            CoroutinePuzzleFlowErrorMessages.requestedValuesAfterCompletion("the numbers Flow"),
        )
    }

    @Test
    fun `requesting fewer flow values than expected reports the unfinished flow`() = runTestWithRandomizedDispatchOrdering {
        val result = coroutinePuzzle {
            emitNumber.expectingFlowCollector().use { collectors ->
                collectors.use { (_, emit) ->
                    emit(7)
                    emit(8)
                }
            }
        }.solve {
            emitNumber.submitCall(WithCallId(1, Unit))
        }

        result.assertIsNotOk<CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure>()
        assertContains(
            result.toMessage(),
            CoroutinePuzzleFlowErrorMessages.stoppedBeforeAllValues("the numbers Flow"),
        )
    }

    @Test
    fun `internal calls are NOT shown in history of error message`() = runTestWithRandomizedDispatchOrdering {
        val publicEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("public")
        coroutinePuzzle {
            callLifetime.expectCall(Unit)
        }.solve {
            callLifetime.submitCall(Unit)
            publicEndpoint.submitCall(Unit) // Should result in error
        }
            .assertIsNotOk()
            .toMessage()
            .assert({ "lifetime" !in it.lowercase() }) { "Message must not mention internal endpoint" }
    }

    @Test
    fun `empty puzzle works with empty solution`() = runTestWithRandomizedDispatchOrdering {
        coroutinePuzzle { }.solve { }.assertIsOk()
    }

    @Test
    fun `internal calls ARE shown in expected calls part of error message`() = runTestWithRandomizedDispatchOrdering {
        val publicEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("public")
        coroutinePuzzle {
            publicEndpoint.expectCall(Unit)
        }.solve {
            callLifetime.submitCall(Unit)
        }
            .assertIsNotOk()
            .toMessage()
            .assert({ "lifetime" in it.lowercase() }) { "Message must mention internal endpoint" }
    }

    class ExceptionForTestBelow : Exception("Test exception")

    @Test
    fun `error that happens in expect call is thrown into submit call`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")
        coroutinePuzzle {
            assertThrows<ExceptionForTestBelow> {
                endpoint.expectCall { throw ExceptionForTestBelow() }
            }
        }.solve {
            assertThrows<ExceptionAcrossRpc> {
                endpoint.submitCall(Unit)
            }
        }
    }

    @Test
    fun `nothing hangs when submit call gets canceled`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")
        val endpointIsRunning = coroutinePuzzleEndPoint<Unit, Unit>("bar")
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
        val endpoint = coroutinePuzzleEndPoint<Int, Int>("foo")
        val endpointIsRunning = coroutinePuzzleEndPoint<Unit, Unit>("bar")
        coroutinePuzzle {
            endpoint.expectCanceledCall {
                endpointIsRunning.expectCall(Unit)
                throw assertThrows<CancellationAcrossRpc> {
                    awaitCancellation()
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
    fun `regular expectCall cancellation of matching submit call DOES throw into coroutine puzzle scope`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")
        val endpointIsRunning = coroutinePuzzleEndPoint<Unit, Unit>("bar")
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
        val endpoint = coroutinePuzzleEndPoint<Int, String>("foo")
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
    fun `trying to call a coroutine puzzle endpoint in parallel while the expectation is synchronous fails`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Int, String>("foo")
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
        val endpoint = coroutinePuzzleEndPoint<Int, String>("foo")
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
        val endpoint = coroutinePuzzleEndPoint<Int, String>("foo")
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
        val endpoint = coroutinePuzzleEndPoint<Int, String>("foo")
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
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")
        coroutinePuzzle {
            endpoint.expectCall(Unit)
        }.solve {
            awaitCancellation()
        }.assertIsNotOk()
    }

    @Test
    fun `quiescence is detected on expectation side`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")
        coroutinePuzzle {
            awaitCancellation()
        }.solve {
            endpoint.submitCall(Unit)
        }.assertIsNotOk()
    }

    @Test
    fun `expectation can await quiescence and inspect unmatched submissions`() = runTestWithRandomizedDispatchOrdering {
        val alreadyExpected = coroutinePuzzleEndPoint<Unit, Unit>("already expected")
        val discovered = coroutinePuzzleEndPoint<Unit, Unit>("discovered")

        coroutinePuzzle {
            launch { alreadyExpected.expectCall(Unit) }

            assertEquals(
                listOf(discovered),
                awaitQuiescenceAndGetUnmatchedSubmissions(),
            )
            discovered.expectCall(Unit)
        }.solve {
            launch { alreadyExpected.submitCall(Unit) }
            discovered.submitCall(Unit)
        }.assertIsOk()
    }

    @Test
    fun `puzzle can finish immediately after a final quiescence check`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")

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
            val outerEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("outer")
            val nestedEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("nested")
            val cancelSolution = CompletableDeferred<Unit>()

            coroutinePuzzle {
                launch {
                    callLifetime.expectCall {
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
                    callLifetime.submitCall(Unit)
                    it.cancel()
                }
            }.assertIsOk()
        }

    @Test
    fun `cancellation after an expectation answered is an explicit failure`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")

        coroutinePuzzle {
            endpoint.expectCall(Unit)
            awaitCancellation()
        }.use { (expectations, submissions) ->
            submissions.send(listOf(
                WithCallId(
                    callId = 1,
                    payload = CoroutinePuzzleSubmissionPayload.CallSubmitted(
                        endpoint.descriptor,
                        Json.encodeToJsonElement(serializer<Unit>(), Unit),
                    ),
                ),
            ))
            expectations.receive().assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Batch>()

            submissions.send(listOf(
                WithCallId(
                    callId = 1,
                    payload = CoroutinePuzzleSubmissionPayload.CallShouldCancel,
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
    fun `cancellating submission before it's sent to the server `() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")

        coroutinePuzzle {
            endpoint.expectCall(Unit)
            awaitCancellation()
        }.use { (expectations, submissions) ->
            submissions.send(listOf(
                WithCallId(
                    callId = 1,
                    payload = CoroutinePuzzleSubmissionPayload.CallSubmitted(
                        endpoint.descriptor,
                        Json.encodeToJsonElement(serializer<Unit>(), Unit),
                    ),
                ),
            ))
            expectations.receive().assertIs<CoroutinePuzzleExpectationBatchOrCompletion.Batch>()

            submissions.send(listOf(
                WithCallId(
                    callId = 1,
                    payload = CoroutinePuzzleSubmissionPayload.CallShouldCancel,
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
    fun `failure teardown is not reported as an unexpected cancellation`() = runTestWithRandomizedDispatchOrdering {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")

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
    else -> junitFail { toMessage() }
}

internal fun ResultsWHistory.assertIsNotOk(): CoroutinePuzzleSolutionResult = result.also {
    assert(it !is CoroutinePuzzleSolutionResult.Success) { "Puzzle succeeded unexpectedly \n${toMessage()}" }
}

@JvmName("assertIsNotOkGeneric")
internal inline fun <reified T: CoroutinePuzzleSolutionResult> ResultsWHistory.assertIsNotOk(): T =
    assertIsNotOk().assertIs<T> { "Expected ${T::class.simpleName} but got ${it!!::class.simpleName}\n${toMessage()}" }

internal inline fun <reified T> Any?.assertIs(
    message: (Any?) -> String = { "Expected instance of ${T::class}, but got $it" },
): T = if (this is T) this else junitFail(message(this))

internal inline fun Any?.assertIs(other: Any?, message: (Any?) -> String) {
    assertEquals(this, message(this))
}

internal inline fun <T> T.assert(test: (T) -> Boolean, message: (T) -> String): T =
    this.also { if (!test(this)) kotlin.test.fail(message(this) + "\nActual value was: $this") }
