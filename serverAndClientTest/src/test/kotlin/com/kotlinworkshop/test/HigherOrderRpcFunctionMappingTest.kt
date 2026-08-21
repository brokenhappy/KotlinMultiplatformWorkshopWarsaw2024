package com.kotlinworkshop.test

import kmpworkshop.common.Either
import kmpworkshop.common.ExceptionThrownAcrossRpcBorder
import kmpworkshop.common.addingServerSideCoroutineScopeReceivers
import kmpworkshop.common.removingClientSideCoroutineScopeReceivers
import kmpworkshop.common.unwrapFromRpcFlowsApiToMoreFunctionalApi
import kmpworkshop.common.wrapFromMoreFunctionalApiToMoreRpcFlowsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

class HigherOrderRpcFunctionMappingTest {
    @Test
    fun `can map 2nd higher order function with many parallel calls`(): Unit = runTestWithRandomizedDispatchOrdering {
        assertMappedVersionPerformsTheSameAsUnmapped(
            raw = { int, function ->
                channelFlow {
                    repeat(20) { number ->
                        launch {
                            val string = "${int + number}"
                            send(string to function(string) { long, double ->
                                long + double
                            }.toInt())
                        }
                    }
                }.toList().sortedBy { it.toString() }
            },
            mapper = { raw: suspend (Int, suspend (String, suspend (Long, Double) -> Double) -> String) -> List<Pair<String, Int>> ->
                raw
                    .addingServerSideCoroutineScopeReceivers { it.addingServerSideCoroutineScopeReceivers() }
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi { it.wrapFromMoreFunctionalApiToMoreRpcFlowsApi() }
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi { it.unwrapFromRpcFlowsApiToMoreFunctionalApi() }
                    .removingClientSideCoroutineScopeReceivers { it }
            },
        ) { function ->
            channelFlow {
                for (outerNumber in 31..40) {
                    launch {
                        Either.Right(
                            outerNumber to function(outerNumber) { string, function ->
                                coroutineScope {
                                    for (number in 21..30) {
                                        launch {
                                            send(Either.Right(Triple(
                                                number.toLong(),
                                                number.toDouble(),
                                                (string.toDouble() + function(number.toLong(), number.toDouble())).toInt().toString(),
                                            )))
                                        }
                                    }
                                }
                                string + "1"
                            }.toList().sortedBy { it.toString() }
                        )
                    }
                }
            }
        }
    }

    // TODO: Test what happens when you break structured concurrency (call nested function after parent call done).

    @Test
    fun `can map 2nd higher calls`(): Unit = runTestWithRandomizedDispatchOrdering {
        assertMappedVersionPerformsTheSameAsUnmapped(
            raw = { int, function ->
                function("4") { long, unit ->
                    long + unit
                }.toInt() + int
            },
            mapper = { raw: suspend (Int, suspend (String, suspend (Long, Double) -> Double) -> String) -> Int ->
                raw
                    .addingServerSideCoroutineScopeReceivers { it.addingServerSideCoroutineScopeReceivers() }
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi { it.wrapFromMoreFunctionalApiToMoreRpcFlowsApi() }
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi { it.unwrapFromRpcFlowsApiToMoreFunctionalApi() }
                    .removingClientSideCoroutineScopeReceivers { it }
            },
        ) { function ->
            function(1) { string, function ->
                (string.toDouble() + function(20L, 42.0)).toInt().toString()
            }
        }
    }

    @Test
    fun `can map higher order function highly parallel`(): Unit = runTestWithRandomizedDispatchOrdering {
        assertMappedVersionPerformsTheSameAsUnmapped(
            raw = { int, function ->
                channelFlow {
                    repeat(20) { number ->
                        launch {
                            send("$number" to function("$number", Unit).toInt() + int)
                        }
                    }
                }.toList().sortedBy { it.toString() }
            },
            mapper = { raw: suspend (Int, suspend (String, Unit) -> String) -> List<Pair<String, Int>> ->
                raw
                    .addingServerSideCoroutineScopeReceivers { it }
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi { it }
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi { it }
                    .removingClientSideCoroutineScopeReceivers { it }
            },
        ) { function ->
            channelFlow {
                (21..40).forEach { number ->
                    launch {
                        send(number to function(number) { string, _ ->
                            string + "1"
                        })
                    }
                }
            }.toList().sortedBy { it.toString() }
        }
    }

    @Test
    fun `can map higher order function`(): Unit = runTestWithRandomizedDispatchOrdering {
        assertMappedVersionPerformsTheSameAsUnmapped(
            raw = { int, function ->
                function("4", Unit).toInt() + int
            },
            mapper = { raw: suspend (Int, suspend (String, Unit) -> String) -> Int ->
                raw
                    .addingServerSideCoroutineScopeReceivers { it }
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi { it }
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
                    .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi()
                    .unwrapFromRpcFlowsApiToMoreFunctionalApi { it }
                    .removingClientSideCoroutineScopeReceivers { it }
            },
        ) { function ->
            assertEquals(
                42,
                function(1) { string, _ ->
                    string + "1"
                }
            )
        }
    }

    private fun createRpcified3rdHigherOrderFunction(
        raw: suspend CoroutineScope.(Unit, suspend (Unit, suspend CoroutineScope.(Unit, Unit) -> Unit) -> Unit) -> Unit,
    ) = raw
        .wrapFromMoreFunctionalApiToMoreRpcFlowsApi { it.wrapFromMoreFunctionalApiToMoreRpcFlowsApi() }
        .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
        .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
        .wrapFromMoreFunctionalApiToMoreRpcFlowsApi()
        .unwrapFromRpcFlowsApiToMoreFunctionalApi()
        .unwrapFromRpcFlowsApiToMoreFunctionalApi()
        .unwrapFromRpcFlowsApiToMoreFunctionalApi()
        .unwrapFromRpcFlowsApiToMoreFunctionalApi { it.unwrapFromRpcFlowsApiToMoreFunctionalApi() }

    private class ExceptionInterceptor(var exception: Throwable? = null)
    private inline fun <T> ExceptionInterceptor.interceptExceptions(block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        exception = t
        throw t
    }
    private inline fun ExceptionInterceptor.expectException(message: () -> String = { "Unexpected null" }): Throwable =
        exception.assertNotNull(message)

    @Test
    fun `exceptions are thrown through the call stack`(): Unit = runTestWithRandomizedDispatchOrdering {
        rethrowingFromCoroutineExceptionHandler { // <- Makes sure we don't swallow CEH exceptions
            val inner = ExceptionInterceptor()
            val middle = ExceptionInterceptor()
            assertThrows<ExceptionThrownAcrossRpcBorder>({ "On the other side, the exception is unknown" }) {
                createRpcified3rdHigherOrderFunction { _, function ->
                    middle.interceptExceptions {
                        function(Unit) { _, _ -> throw IllegalArgumentException() }
                    }
                }(Unit) { _, function ->
                    inner.interceptExceptions {
                        function(Unit, Unit)
                    }
                }
            }
            inner.expectException().assertIs<ExceptionThrownAcrossRpcBorder> { "On the other side, the exception is unknown" }
            middle.expectException().assertIs<IllegalArgumentException> { "On this side, the exception is known" }
        }
    }

    @Test
    fun `test rethrowingFromCoroutineExceptionHandler`() = runTestWithRandomizedDispatchOrdering {
        val exception = IllegalArgumentException("Will be thrown")
        val caughtException = assertThrows<IllegalArgumentException> {
            rethrowingFromCoroutineExceptionHandler {
                supervisorScope {
                    launch { throw exception }
                }
            }
        }

        val causes = generateSequence<Throwable>(caughtException) { it.cause }.distinct().toList()
        assert(causes.any { it == exception }) {
            "Original exception must rethrown, instead threw: ${causes.joinToString("\n")}"
        }
    }

    @Test
    fun `test rethrowingFromCoroutineExceptionHandler cancels work upon exception`() = runTestWithRandomizedDispatchOrdering {
        assertThrows<IllegalArgumentException> {
            rethrowingFromCoroutineExceptionHandler {
                supervisorScope {
                    launch { throw IllegalArgumentException() }
                    launch { awaitCancellation() }
                }
            }
        }
    }
}

private suspend fun rethrowingFromCoroutineExceptionHandler(block: suspend CoroutineScope.() -> Unit) {
    val exception = CompletableDeferred<Throwable?>()
    withContext(CoroutineExceptionHandler { _, it -> exception.complete(it) }) {
        launch { exception.await()?.let { throw it } }
        coroutineScope(block)
        exception.complete(null)
    }
}

private inline fun <T, R> assertMappedVersionPerformsTheSameAsUnmapped(raw: T, noinline mapper: (T) -> T, application: (T) -> R) {
    listOf(raw, mapper(raw))
        .map { application(it) }
        .also { (lhs, rhs) ->
            assertEquals(lhs, rhs) { "Results of both computations differed" }
        }
}

internal inline fun <reified T : Any> T?.assertNotNull(message: () -> String): T = assertIs<T> { message() }

// TODO: Try to kill executor during cancellation. Are cancellation awaiting semantics still preserved?
