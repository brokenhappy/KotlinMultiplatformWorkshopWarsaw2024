package com.kotlinworkshop.test

import kmpworkshop.client.toMessage
import kmpworkshop.common.*
import kmpworkshop.server.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kmpworkshop.common.CoroutinePuzzleResultWithHistory as ResultsWHistory
import org.junit.jupiter.api.fail as junitFail

class CoroutinePuzzleTest {
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
            expectingMatchedParallelism {
                launch {
                    endpoint.expectCall { it.toString() }
                }
                endpoint.expectCall { it.toString() }
            }
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
            expectingMatchedParallelism {
                endpoint.expectCall { it.toString() }
            }
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
            expectingMatchedParallelism {
                launch { endpoint.expectCall { it.toString() } }
                launch { endpoint.expectCall { it.toString() } }
                endpoint.expectCall { it.toString() }
            }
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
            expectingMatchedParallelism {
                launch { endpoint.expectCall { it.toString() } }
                endpoint.expectCall { it.toString() }
            }
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
            expectingMatchedParallelism {
                launch { endpoint.expectCall { it.toString() } }
                launch { endpoint.expectCall { it.toString() } }
                endpoint.expectCall { it.toString() }
            }
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
