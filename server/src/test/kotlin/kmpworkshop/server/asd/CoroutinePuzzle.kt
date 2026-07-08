package kmpworkshop.server.asd

import kmpworkshop.server.AutoBatchedFunctionId
import kmpworkshop.server.autoBatchedOnQuiescence
import kmpworkshop.server.withInterceptingDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import org.junit.jupiter.api.fail as junitFail
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

data class CoroutinePuzzleEndPoint<in T, out R>(val descriptor: CoroutinePuzzleEndPointDescriptor)

@Serializable
data class CoroutinePuzzleEndPointDescriptor(val description: String, val isHiddenInHistory: Boolean = false)

fun CoroutinePuzzleEndPointDescriptor.toEndpoint(): CoroutinePuzzleEndPoint<*, *> =
    CoroutinePuzzleEndPoint<Any?, Any?>(this)

fun <T, R> coroutinePuzzleEndPoint(description: String, isHiddenInHistory: Boolean = false): CoroutinePuzzleEndPoint<T, R> =
    CoroutinePuzzleEndPoint(CoroutinePuzzleEndPointDescriptor(description, isHiddenInHistory))

class CoroutinePuzzleState(
    val branchCount: Int,
    val expectedCalls: List<List<CoroutinePuzzleEndPointWaitingState<*, *>>>,
) {
    fun copy(
        branchCount: Int = this.branchCount,
        expectedCalls: List<List<CoroutinePuzzleEndPointWaitingState<*, *>>> = this.expectedCalls,
    ): CoroutinePuzzleState = CoroutinePuzzleState(branchCount, expectedCalls)

    override fun toString(): String = """CoroutinePuzzleState(
        branchCount=$branchCount,
        currentExpectedCalls=$expectedCalls,
    )""".trimIndent()
}

class CoroutinePuzzleEndPointWaitingState<T, R>(
    val endPoint: CoroutinePuzzleEndPoint<T, R>,
    /** Used to make sure that multiple submits will not submit the same expected call */
    var isTaken: Boolean,
    /** Is called from the submission side */
    val submitCall: suspend (JsonElement) -> JsonElement,
) {
    override fun toString(): String = """WaitingState(endPoint=${endPoint.descriptor.description}, isTaken=$isTaken)""".trimIndent()
}


data class CoroutinePuzzle(
    val puzzle: suspend CoroutineScope.(MutableStateFlow<CoroutinePuzzleState>) -> Unit,
)

interface CoroutinePuzzleSolutionScope {
    suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement
}

context(solutionScope: CoroutinePuzzleSolutionScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint<T, R>.submitCall(t: T): R =
    Json.decodeFromJsonElement<R>(submitRawCall(Json.encodeToJsonElement<T>(t)))

context(solutionScope: CoroutinePuzzleSolutionScope)
suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement =
    with(solutionScope) { submitRawCall(t) }

@Serializable
sealed class CoroutinePuzzleSolutionResult {
    @Serializable
    data object Success : CoroutinePuzzleSolutionResult()
    @Serializable
    data class Failure(val description: String) : CoroutinePuzzleSolutionResult()
}

context(_: CoroutinePuzzleSolutionScope)
internal fun fail(
    message: String,
    /** Whether upon printing the failure, one should add the history of actions (sometimes you rather print it manually) */
    includeHistory: Boolean = true,
): Nothing = throw CoroutinePuzzleFailedControlFlowException(message, includeHistory = false)

class CoroutinePuzzleFailedControlFlowException(
    message: String,
    val includeHistory: Boolean,
) : Exception(message, null) // TODO: Optimize away stacktrace hydration?

suspend fun CoroutinePuzzle.solve(solution: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit): CoroutinePuzzleSolutionResult {
    val history = MutableStateFlow<List<CoroutinePuzzleEndPoint<*, *>>>(emptyList())
    val stateRemoverLock = Mutex()
    return try {
        coroutineScope {
            val puzzleState = MutableStateFlow(CoroutinePuzzleState(branchCount = 1, expectedCalls = emptyList()))
            launch {
                puzzle(puzzleState)
            }
            object : CoroutinePuzzleSolutionScope {
                override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement {
                    val state = puzzleState.mapNotNull { currentState ->
                        val expectedCalls = currentState.expectedCalls.filterNot { it.isTaken }
                        if (currentState.branchCount < currentState.expectedCalls.size) error("Wth happened?")
                        else stateRemoverLock.withLock {
                            expectedCalls
                                .firstOrNull { it.endPoint == this }
                                ?.also { it.isTaken = true }
                                .also {
                                    if (it == null && currentState.branchCount == expectedCalls.size) fail(
                                        """
                                            |The history of actions was:
                                            |${
                                                history
                                                    .value
                                                    .filterNot { it.descriptor.isHiddenInHistory }
                                                    .joinToString("\n") { it.descriptor.description }
                                            }
                                            ${
                                                expectedCalls.map { it.endPoint.descriptor.description }.distinct()
                                                    .let { expectedCalls ->
                                                        when (expectedCalls.size) {
                                                            0 -> "|And no more actions were expected to be made."
                                                            1 -> "|And now the expected action is: ${expectedCalls.first()}"
                                                            else -> """
                                                                |And now the expected actions are either:
                                                                |${expectedCalls.joinToString(",\n or ")}
                                                            """.trimIndent()
                                                        }
                                                    }
                                            }
                                            |But instead you did: ${descriptor.description}
                                        """.trimMargin(),
                                        includeHistory = false,
                                    )
                                }
                        }
                    }.first()

                    return state.submitCall.also { history.update { it + this@submitRawCall } }.invoke(t)
                }
            }.let { coroutineScope { context(it) { solution(this) } } }

            fun failBecauseLeftovers(message: String): Nothing =
                throw CoroutinePuzzleFailedControlFlowException(message, includeHistory = true)
            if (history.value.isEmpty()) puzzleState.first { it.expectedCalls.isNotEmpty() } // Wait for first expected call
            puzzleState
                .onEach(::println)
                .first { it.expectedCalls.size == it.branchCount } // Wait to reach all expect calls
            val leftoverExpectedCalls = stateRemoverLock.withLock {
                puzzleState.value.expectedCalls.filterNot { it.isTaken }
            }
            val distinctLeftoverExpectedCalls = leftoverExpectedCalls.map { it.endPoint.descriptor.description }.distinct()
            when (distinctLeftoverExpectedCalls.size) {
                0 -> { /* All is OK! */ }
                1 -> failBecauseLeftovers(
                    "You made too few function calls. We're still expecting: ${distinctLeftoverExpectedCalls.single()}"
                )
                else -> failBecauseLeftovers("""
                    |You made too few function calls, we were expecting one of:
                    |${distinctLeftoverExpectedCalls.joinToString(",\n or ")}
                """.trimMargin())
            }
            CoroutinePuzzleSolutionResult.Success
        }
    } catch (e: CoroutinePuzzleFailedControlFlowException) {
        CoroutinePuzzleSolutionResult.Failure("""
            |${e.message}
            |${
                if (e.includeHistory) """
                    |The history of actions was:
                    |${
                        history
                            .value
                            .filterNot { it.descriptor.isHiddenInHistory }
                            .joinToString("\n") { it.descriptor.description }
                    }
                """.trimMargin() 
                else ""
            }
        """.trimMargin())
    }
}

context(_: CoroutinePuzzleBuilderScope)
fun fail(message: String): Nothing = throw CoroutinePuzzleFailedControlFlowException(message, true)

context(builder: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint</* @Exact */T, /* @Exact */R>.expectCall(
    noinline valueProducer: suspend context(CoroutinePuzzleExpectationScope) (T) -> R,
): T = builder.expectCallTo(this, serializer(), serializer(), valueProducer)

context(builder: CoroutinePuzzleBuilderScope)
inline fun verify(condition: Boolean, message: () -> String) {
    if (!condition) fail(message())
}
context(builder: CoroutinePuzzleBuilderScope)
inline fun <T : Any> T?.verifyNotNull(message: () -> String): T = this ?: fail(
    message()
)

context(builder: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint</* @Exact */ R, /* @Exact */ T>.expectCall(
    value: T,
): R = expectCall { value }

interface CoroutinePuzzleExpectationScope {
    suspend fun awaitSubmissionCancellation(): Nothing
}

context(expectationScope: CoroutinePuzzleExpectationScope)
suspend fun awaitCancellationOfMatchingSubmitCall(): Nothing = expectationScope.awaitSubmissionCancellation()

interface CoroutinePuzzleBuilderScope {
    suspend fun <T, R> expectCallTo(
        endPoint: CoroutinePuzzleEndPoint<T, R>,
        tSerializer: KSerializer<T>,
        rSerializer: KSerializer<R>,
        valueProducer: suspend context(CoroutinePuzzleExpectationScope) (T) -> R,
    ): T

    suspend fun <T> expectingMatchedParallelism(block: CoroutineScope.() -> T): T
}

fun coroutinePuzzle(
    builder: suspend context(CoroutinePuzzleBuilderScope) () -> Unit,
): CoroutinePuzzle = CoroutinePuzzle { stateFlow ->
    fun resumeAll(expectations: List<CoroutinePuzzleEndPointWaitingState<*, *>>) {
        stateFlow.update { old ->
            old.copy(expectedCalls = old.expectedCalls.plusElement(expectations))
        }
    }

    val coroutinePuzzleSubmissionFunction = AutoBatchedFunctionId(
        fallbackOutOfBatchScope = { resumeAll(listOf(it)) },
        batchResumer = { batch ->
            resumeAll(batch.map { it.query })
            batch.forEach { it.continuation.resume(Unit) }
        }
    )

    withInterceptingDispatcher(
        onDispatchScheduled = {
            stateFlow.update { it.copy(branchCount = it.branchCount + 1) }
        },
        onDispatchedRunnableComplete = {
            stateFlow.update { it.copy(branchCount = it.branchCount - 1) }
        },
    ) {
        context(object : CoroutinePuzzleBuilderScope {
            override suspend fun <T, R> expectCallTo(
                endPoint: CoroutinePuzzleEndPoint<T, R>,
                tSerializer: KSerializer<T>,
                rSerializer: KSerializer<R>,
                valueProducer: suspend context(CoroutinePuzzleExpectationScope) (T) -> R,
            ): T {
                val argumentDeferred = CompletableDeferred<T>()
                val resultDeferred = CompletableDeferred<R>()
                val submissionIsCancelled = CompletableDeferred<CancellationException>()
                val state = CoroutinePuzzleEndPointWaitingState(
                    endPoint = endPoint,
                    isTaken = false,
                    submitCall = { givenValue ->
                        // This function is being called from the submission side, our context is unknown here.
                        // So we only do `complete` and `await`, and let the work be done on the "expectCall" side
                        argumentDeferred.completeWithResultOf { (Json.decodeFromJsonElement(tSerializer, givenValue)) }
                        Json.encodeToJsonElement(
                            serializer = rSerializer,
                            value = try {
                                resultDeferred.await()
                            } catch (c: CancellationException) {
                                if (!currentCoroutineContext().isActive) {
                                    submissionIsCancelled.complete(c)
                                }
                                throw c
                            },
                        )
                    },
                )
                coroutinePuzzleSubmissionFunction.batched(state)
                return argumentDeferred.await().also { argument ->
                    try {
                        context(
                            object: CoroutinePuzzleExpectationScope {
                                override suspend fun awaitSubmissionCancellation(): Nothing {
                                    throw submissionIsCancelled.await()
                                }
                            }
                        ) {
                            resultDeferred.complete(valueProducer(argument)) // Try to produce value on the "expectCall" side
                        }
                    } catch (failedException: CoroutinePuzzleFailedControlFlowException) {
                        // Don't complete resultDeferred.
                        // Just let the solving side wait, since they will get canceled shortly.
                        throw failedException
                    } catch (t: Throwable) {
                        resultDeferred.completeExceptionally(t)
                    }
                }
            }

            override suspend fun <T> expectingMatchedParallelism(block: CoroutineScope.() -> T): T {
                @OptIn(ExperimentalTime::class)
                return coroutinePuzzleSubmissionFunction.autoBatchedOnQuiescence {
                    block()
                }
            }
        }) {
            builder()
        }
    }
}

private suspend inline fun MutableStateFlow<CoroutinePuzzleState>.update2(function: (CoroutinePuzzleState) -> CoroutinePuzzleState) {
//    println("${currentCoroutineContext().job.hashCode()} => ${updateAndGet(function)}")
    update(function)
}

class CoroutinePuzzleUtilitiesTest {
    @Test
    fun `internal calls are NOT shown in history of error message`() = runTest {
        val internalEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("internal", isHiddenInHistory = true)
        val publicEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("public", isHiddenInHistory = false)
        coroutinePuzzle {
            internalEndpoint.expectCall(Unit)
        }.solve {
            internalEndpoint.submitCall(Unit)
            publicEndpoint.submitCall(Unit) // Should result in error
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure>()
            .description
            .assert({ "internal" !in it.lowercase() }) { "Message must not mention internal endpoint" }
    }

    @Test
    fun `internal calls ARE shown in expected calls part of error message`() = runTest {
        val internalEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("internal", isHiddenInHistory = true)
        val publicEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("public", isHiddenInHistory = false)
        coroutinePuzzle {
            publicEndpoint.expectCall(Unit)
        }.solve {
            internalEndpoint.submitCall(Unit)
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure>()
            .description
            .assert({ "internal" in it.lowercase() }) { "Message must mention internal endpoint" }
    }

    class ExceptionForTestBelow() : Exception("Test exception")

    @Test
    fun `error that happens in expect call is thrown into submit call`() = runTest {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")
        coroutinePuzzle {
            endpoint.expectCall { throw ExceptionForTestBelow() }
        }.solve {
            assertThrows<ExceptionForTestBelow> {
                endpoint.submitCall(Unit)
            }
        }
    }

    @Test
    fun `nothing hangs when submit call gets canceled`() = runTest {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")
        val cancellationStartHook = CompletableDeferred<Unit>()
        val cancellationFinishedHook = CompletableDeferred<Unit>()
        coroutinePuzzle {
            endpoint.expectCall {
                cancellationStartHook.complete(Unit)
                cancellationFinishedHook.await()
            }
        }.solve {
            launch {
                endpoint.submitCall(Unit)
            }.sideEffect {
                cancellationStartHook.await()
                it.cancelAndJoin()
                cancellationFinishedHook.complete(Unit)
            }
        }
    }

    class SpecialCancellationExceptionForTestBelow() : CancellationException()

    @Test
    fun `await cancellation of matching submit call does not throw into coroutine puzzle scope`() = runTest {
        val endpoint = coroutinePuzzleEndPoint<Unit, Unit>("foo")
        val cancellationStartHook = CompletableDeferred<Unit>()
        coroutinePuzzle {
            try {
                endpoint.expectCall {
                    cancellationStartHook.complete(Unit)
                    assertThrows<SpecialCancellationExceptionForTestBelow> {
                        awaitCancellationOfMatchingSubmitCall()
                    }
                    Unit
                }
            } catch (t: Throwable) {
                junitFail("Exception should not be thrown, not even cancellation", t)
            }
        }.solve {
            launch {
                endpoint.submitCall(Unit)
            }.sideEffect {
                cancellationStartHook.await()
                it.cancel(SpecialCancellationExceptionForTestBelow())
            }
        }
    }
}

private fun CoroutinePuzzleSolutionResult.assertIsOk(): Unit = when (this) {
    is CoroutinePuzzleSolutionResult.Failure -> junitFail { this.description }
    CoroutinePuzzleSolutionResult.Success -> { /** All OK! */ }
}

private fun CoroutinePuzzleSolutionResult.assertIsNotOk() {
    assertIs<CoroutinePuzzleSolutionResult.Failure> { "Puzzle succeeded unexpectedly" }
}

internal inline fun <reified T> Any?.assertIs(
    message: (Any?) -> String = { "Expected instance of ${T::class}, but got $it" },
): T = if (this is T) this else junitFail(message(this))

internal inline fun Any?.assertIs(other: Any?, message: (Any?) -> String) {
    assertEquals(this, message(this))
}

internal inline fun <T> T.assert(test: (T) -> Boolean, message: (T) -> String): T =
    this.also { if (!test(this)) junitFail(message(this) + "\nActual value was: $this") }

inline fun <T> T.sideEffect(function: (T) -> Unit) {
    function(this)
}

@OptIn(ExperimentalContracts::class)
inline fun <T> CompletableDeferred<Unit>.completeAfter(function: () -> T): T {
    contract { callsInPlace(function, InvocationKind.EXACTLY_ONCE) }
    val result: T
    completeWithResultOf { function().also { result = it }; }
    return result
}

@OptIn(ExperimentalContracts::class)
inline fun <T> CompletableDeferred<T>.completeWithResultOf(function: () -> T): T {
    contract { callsInPlace(function, InvocationKind.EXACTLY_ONCE) }
    return try {
        function().also { complete(it) }
    } catch (t: Throwable) {
        completeExceptionally(t)
        throw t
    }
}

@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@OptIn(ExperimentalContracts::class)
public inline fun <T> MutableStateFlow<T>.updateWithContract(function: (T) -> T) {
    contract { this.callsInPlace(function, InvocationKind.AT_LEAST_ONCE) }
    update(function)
}
