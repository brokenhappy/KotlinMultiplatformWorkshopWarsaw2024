package kmpworkshop.server.asd

import kmpworkshop.common.AutoBatchedFunctionId
import kmpworkshop.server.asd.CoroutinePuzzleSolutionResult.Failure.Reason.ExactParallelismMismatch
import kmpworkshop.server.asd.CoroutinePuzzleSolutionResult.Failure.Reason.MoreExpectationsThanSubmissions
import kmpworkshop.server.asd.CoroutinePuzzleSolutionResult.Failure.Reason.MoreSubmissionsThanExpectations
import kmpworkshop.server.asd.CoroutinePuzzleSolutionResult.Failure.Reason.UnexpectedSubmissions
import kmpworkshop.server.asd.assertIs
import kmpworkshop.common.autoBatchedOnQuiescence
import kmpworkshop.common.withInterceptingDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.collections.forEach
import kotlin.collections.map
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import org.junit.jupiter.api.fail as junitFail
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

data class CoroutinePuzzleEndPoint<in T, out R>(val descriptor: CoroutinePuzzleEndPointDescriptor)

@Serializable
data class CoroutinePuzzleEndPointDescriptor(
    /**
     * This property has 2 functions.
     *  - It's visible to the user. Reading it makes it clear to the user what function call caused this or they should make.
     *  - It must be unique per puzzle. Expectations use this as a key to map to the submissions.
     */
    val description: String,
    /**
     * The history refers to the list of submissions made during a solve attempt.
     * When the solve attempt fails, the history will be shown to the user to give them more insight.
     */
    val isHiddenInHistory: Boolean = false, // TODO: Make it a solution feature?
)

fun CoroutinePuzzleEndPointDescriptor.toEndpoint(): CoroutinePuzzleEndPoint<*, *> =
    CoroutinePuzzleEndPoint<Any?, Any?>(this)

fun <T, R> coroutinePuzzleEndPoint(description: String, isHiddenInHistory: Boolean = false): CoroutinePuzzleEndPoint<T, R> =
    CoroutinePuzzleEndPoint(CoroutinePuzzleEndPointDescriptor(description, isHiddenInHistory))

sealed class CoroutinePuzzleState {
    class WaitingForExpectations(
        val expectedCallsLeftAfterLastResumption: List<CoroutinePuzzleEndPointWaitingState<*, *>>,
    ) : CoroutinePuzzleState()
    class WaitingForSubmissions(
        val expectedCalls: List<CoroutinePuzzleEndPointWaitingState<*, *>>,
        val isStrictParallelism: Boolean,
    ) : CoroutinePuzzleState()
    data object ExpectationsDone : CoroutinePuzzleState()
}

class CoroutinePuzzleEndPointWaitingState<T, R>(
    val endPoint: CoroutinePuzzleEndPoint<T, R>,
    val isStrictParallelism: Boolean,
    /** Is called from the submission side */
    val submitCall: suspend (JsonElement) -> JsonElement,
) {
    override fun toString(): String = """WaitingState(endPoint=${endPoint.descriptor.description})""".trimIndent()
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

@Serializable sealed class CoroutinePuzzleSolutionResult {
    @Serializable data object Success : CoroutinePuzzleSolutionResult()
    @Serializable data class Failure(
        val history: List<CoroutinePuzzleEndPointDescriptor>,
        val reason: Reason,
    ) : CoroutinePuzzleSolutionResult() {
        @Serializable sealed class Reason {
            @Serializable data class MoreSubmissionsThanExpectations(
                val overshotSubmissions: List<CoroutinePuzzleEndPointDescriptor>,
            ) : Reason()
            @Serializable data class MoreExpectationsThanSubmissions(
                val expectedFollowups: List<CoroutinePuzzleEndPointDescriptor>,
            ) : Reason()
            @Serializable data class ExactParallelismMismatch(
                val submissions: List<CoroutinePuzzleEndPointDescriptor>,
                val expectations: List<CoroutinePuzzleEndPointDescriptor>,
            ) : Reason()
            @Serializable data class UnexpectedSubmissions(
                val unexpectedSubmissions: List<CoroutinePuzzleEndPointDescriptor>,
                val expectations: List<CoroutinePuzzleEndPointDescriptor>,
            ) : Reason()
            @Serializable data class Custom(val message: String) : Reason()
        }
    }
}

context(_: CoroutinePuzzleSolutionScope)
internal fun fail(reason: CoroutinePuzzleSolutionResult.Failure.Reason): Nothing =
    throw CoroutinePuzzleFailedControlFlowException(reason)

class CoroutinePuzzleFailedControlFlowException(
    val reason: CoroutinePuzzleSolutionResult.Failure.Reason,
) : Exception(null, null) // TODO: Optimize away stacktrace hydration?

suspend fun CoroutinePuzzle.solve(solution: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit): CoroutinePuzzleSolutionResult {
    val history = MutableStateFlow<List<CoroutinePuzzleEndPoint<*, *>>>(emptyList())
    return try {
        val puzzleState = MutableStateFlow<CoroutinePuzzleState>(CoroutinePuzzleState.WaitingForExpectations(emptyList()))
        val coroutinePuzzleSubmissionFunction = AutoBatchedFunctionId<SubmissionCall, JsonElement?>(
            batchResumer = { submissionCalls ->
                val submissions = submissionCalls.map { it.query }
                val expectations = puzzleState
                    .takeWhile { it !is CoroutinePuzzleState.ExpectationsDone }
                    .filterIsInstance<CoroutinePuzzleState.WaitingForSubmissions>()
                    .firstOrNull()
                    ?: throw CoroutinePuzzleFailedControlFlowException(MoreSubmissionsThanExpectations(
                        submissions.map { it.endPoint.descriptor },
                    ))

                val expectedDescriptors = expectations.expectedCalls.map { it.endPoint.descriptor }.toSet()
                if (expectations.isStrictParallelism) {
                    if (
                        expectations.expectedCalls.size != submissions.size ||
                        expectedDescriptors != submissions.map { it.endPoint.descriptor }.toSet()
                    ) {
                        throw CoroutinePuzzleFailedControlFlowException(ExactParallelismMismatch(
                            submissions.map { it.endPoint.descriptor },
                            expectations.expectedCalls.map { it.endPoint.descriptor },
                        ))
                    }
                } else {
                    val missingSubmissions = submissions.filter { it.endPoint.descriptor !in expectedDescriptors }
                    if (missingSubmissions.size == submissions.size) {
                        throw CoroutinePuzzleFailedControlFlowException(UnexpectedSubmissions(
                            unexpectedSubmissions = submissions.map { it.endPoint.descriptor },
                            expectations = expectations.expectedCalls.map { it.endPoint.descriptor },
                        ))
                    }
                }


                // This makes sure that multiple expectations to the same endpoint are not processed multiple times.
                val processedExpectations = Collections.newSetFromMap<CoroutinePuzzleEndPointWaitingState<*, *>>(IdentityHashMap())
                coroutineScope {
                    val submissionsReadyToGo = submissionCalls.map { submissionCall ->
                        val matchingCall = expectations
                            .expectedCalls
                            .firstOrNull { it !in processedExpectations && it.endPoint.descriptor == submissionCall.query.endPoint.descriptor }
                            ?.also { processedExpectations.add(it) }

                        suspend { // We don't want to persist the side effect yet. We want to update the state first.
                            if (!submissionCall.continuation.isCancelled) {
                                launch {
                                    submissionCall.continuation.resumeWith(
                                        // The submissions that are not matched need to retry (when we return null)
                                        runCatching { matchingCall?.submitCall(submissionCall.query.argument) },
                                    )
                                }.also { job ->
                                    submissionCall.invokeOnCancellation {
                                        job.cancel()
                                    }
                                }
                            }
                        }
                    }
                    puzzleState.update { oldState ->
                        if (oldState !== expectations) throw IllegalStateException("Unexpected puzzle state $oldState")
                        CoroutinePuzzleState.WaitingForExpectations(expectations.expectedCalls.filter { it !in processedExpectations })
                    }
                    submissionsReadyToGo.forEach { it() }
                }
            }
        )
        coroutineScope {
            launch {
                puzzle(puzzleState)
            }
            context(
                object : CoroutinePuzzleSolutionScope {
                    override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement {
                        while (true) {
                            return coroutinePuzzleSubmissionFunction.batched(SubmissionCall(this, t)) ?: continue
                        }
                    }
                },
            ) {
                @OptIn(ExperimentalTime::class)
                coroutinePuzzleSubmissionFunction.autoBatchedOnQuiescence { solution(this) }
            }
            puzzleState
                .takeWhile { it !is CoroutinePuzzleState.ExpectationsDone }
                .filterIsInstance<CoroutinePuzzleState.WaitingForSubmissions>()
                .firstOrNull()
                ?.let {
                    throw CoroutinePuzzleFailedControlFlowException(MoreExpectationsThanSubmissions(
                        it.expectedCalls.map { it.endPoint.descriptor }
                    ))
                } ?: CoroutinePuzzleSolutionResult.Success
        }
    } catch (e: CoroutinePuzzleFailedControlFlowException) {
        CoroutinePuzzleSolutionResult.Failure(history.value.map { it.descriptor }, e.reason)
    }
}

data class SubmissionCall(val endPoint: CoroutinePuzzleEndPoint<*, *>, val argument: JsonElement)

fun CoroutinePuzzleSolutionResult.Failure.toMessage(): String = """
    |${reason.toMessage()}
    |
    |The history of actions was:
    ${
        history
            .filterNot { it.isHiddenInHistory }
            .joinToString("\n") { "| - ${it.description}" }
    }
""".trimMargin()

fun CoroutinePuzzleSolutionResult.Failure.Reason.toMessage(): String = when (this) {
    is ExactParallelismMismatch -> """
        |You tried to call these at the same time:
        |${formatCallAttemptsWithMargins(submissions.map { it.description }.distinct())}
        |However, you were expected to call exactly these 
    """.trimIndent()
    is MoreExpectationsThanSubmissions -> """
        |You made too few function calls. We're still expecting ${
            expectedFollowups.map { it.description }.distinct().let { expectedCalls ->
                expectedCalls.singleOrNull()
                    ?: """
                        either:
                        |${expectedCalls.joinToString(",\n| or ", postfix = "\n|")}
                    """.trimIndent()
            }
        }
    """.trimMargin()
    is MoreSubmissionsThanExpectations -> """
        |Attempted to call ${formatCallAttemptsWithMargins(overshotSubmissions.map { it.description }.distinct())}
    """.trimMargin()
    is UnexpectedSubmissions -> """
        |Currently the expected ${
            expectations.map { it.description }.distinct().let { expectedCalls ->
                expectedCalls.singleOrNull()?.let { "action is: $it" } 
                    ?: """
                        actions are either:
                        |${expectedCalls.joinToString(",\n| or ")}
                    """.trimIndent()
            }
        }
        |But instead you attempted to call ${
            formatCallAttemptsWithMargins(unexpectedSubmissions.map { it.description }.distinct())
        }
    """.trimMargin()
    is CoroutinePuzzleSolutionResult.Failure.Reason.Custom -> message
}

private fun formatCallAttemptsWithMargins(attempts: List<String>): String =
    attempts.singleOrNull() ?: attempts.joinToString(", and", prefix = "all of these at the same time: \n") {
        "|    $it\n"
    }

context(_: CoroutinePuzzleBuilderScope)
fun fail(reason: CoroutinePuzzleSolutionResult.Failure.Reason): Nothing = throw CoroutinePuzzleFailedControlFlowException(reason)

context(builder: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint</* @Exact */T, /* @Exact */R>.expectCall(
    noinline valueProducer: suspend context(CoroutinePuzzleExpectationScope) (T) -> R,
): T = builder.expectCallTo(this, serializer(), serializer(), valueProducer)

context(builder: CoroutinePuzzleBuilderScope)
suspend fun <T> expectingMatchedParallelism(block: suspend CoroutineScope.() -> T): T =
    builder.expectingMatchedParallelism(block)

context(builder: CoroutinePuzzleBuilderScope)
inline fun verify(condition: Boolean, message: () -> String) {
    if (!condition) fail(CoroutinePuzzleSolutionResult.Failure.Reason.Custom(message()))
}
context(builder: CoroutinePuzzleBuilderScope)
inline fun <T : Any> T?.verifyNotNull(message: () -> String): T =
    this ?: fail(CoroutinePuzzleSolutionResult.Failure.Reason.Custom(message()))

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

    suspend fun <T> expectingMatchedParallelism(block: suspend CoroutineScope.() -> T): T
}

private object InStrictParallelismExpectation : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<InStrictParallelismExpectation>
}

@OptIn(ExperimentalAtomicApi::class)
fun coroutinePuzzle(
    builder: suspend context(CoroutinePuzzleBuilderScope) () -> Unit,
): CoroutinePuzzle = CoroutinePuzzle { stateFlow ->

    val coroutinePuzzleSubmissionFunction = AutoBatchedFunctionId<CoroutinePuzzleEndPointWaitingState<*, *>, Boolean>(
        batchResumer = { batch ->
            assert(batch.map { it.query.isStrictParallelism }.toSet().size == 1)
            val waitingState = CoroutinePuzzleState.WaitingForSubmissions(
                expectedCalls = batch.map { it.query },
                isStrictParallelism = batch.first().query.isStrictParallelism,
            )
            stateFlow.update { old ->
                when (old) {
                    CoroutinePuzzleState.ExpectationsDone,
                    is CoroutinePuzzleState.WaitingForSubmissions ->
                        throw IllegalStateException("Unexpected puzzle state $old")
                    is CoroutinePuzzleState.WaitingForExpectations -> waitingState
                }
            }
            val callsThatDidntHaveMatchingSubmit = stateFlow
                .mapNotNull { current ->
                    when (current) {
                        CoroutinePuzzleState.ExpectationsDone,
                        is CoroutinePuzzleState.WaitingForSubmissions ->
                            if (waitingState === current) null
                            else throw IllegalStateException("Unexpected puzzle state $current")
                        is CoroutinePuzzleState.WaitingForExpectations -> current.expectedCallsLeftAfterLastResumption
                    }
                }
                .first()
                .map { it.endPoint.descriptor }
                .toSet()
            // All expectation calls that did not have a matching submission need to repeat
            batch.forEach { it.continuation.resume(it.query.endPoint.descriptor !in callsThatDidntHaveMatchingSubmit) }
        }
    )
    val branchCount = AtomicInt(0)


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
                isStrictParallelism = currentCoroutineContext()[InStrictParallelismExpectation.Key] != null,
                submitCall = { givenValue ->
                    // This function is being called from the submission side, our context is unknown here.
                    // So we only do `complete` and `await`, and let the work be done on the "expectCall" side
                    argumentDeferred.completeWithResultOf { Json.decodeFromJsonElement(tSerializer, givenValue) }
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
            // Keep batching until successful. (It could return true if the batch is processed, but our expectation isn't satisfied yet)
            while (!coroutinePuzzleSubmissionFunction.batched(state));
            return argumentDeferred.await().also { argument ->
                try {
                    context(
                        object : CoroutinePuzzleExpectationScope {
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

        override suspend fun <T> expectingMatchedParallelism(block: suspend CoroutineScope.() -> T): T {
            require(branchCount.load() == 1) { "expectingMatchedParallelism must only be used without other parallelism" }
            @OptIn(ExperimentalTime::class)
            return withContext(InStrictParallelismExpectation) { block() }
        }
    }) {
        @OptIn(ExperimentalTime::class)
        coroutinePuzzleSubmissionFunction.autoBatchedOnQuiescence {
            withInterceptingDispatcher(
                onDispatchScheduled = {
                    branchCount.incrementAndFetch()
                },
                onDispatchedRunnableComplete = {
                    branchCount.decrementAndFetch()
                },
            ) {
                builder()
            }
        }
        stateFlow.value = CoroutinePuzzleState.ExpectationsDone
    }
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
            .toMessage()
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
            .toMessage()
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

    @Test
    fun `trying to call a coroutine puzzle endpoint synchronously while the expectation is parallel fails`() = runTest {
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
        }.assertIsNotOk().reason.assertIs<ExactParallelismMismatch>()
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint in parallel while the expectation is synchronous fails`() = runTest(timeout = 15.minutes) {
        val endpoint = coroutinePuzzleEndPoint<Int, String>("foo")
        coroutinePuzzle {
            expectingMatchedParallelism {
                endpoint.expectCall { it.toString() }
            }
        }.solve {
            launch { endpoint.submitCall(42) }
            endpoint.submitCall(42)
        }.assertIsNotOk().reason.assertIs<ExactParallelismMismatch>()
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint with double parallel while the expectation is triple parallel fails`() = runTest {
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
        }.assertIsNotOk().reason.assertIs<ExactParallelismMismatch>()
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint with triple parallel while the expectation is double parallel fails`() = runTest {
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
        }.assertIsNotOk().reason.assertIs<ExactParallelismMismatch>()
    }

    @Test
    fun `trying to call a coroutine puzzle endpoint with matching parallelism succeeds`() = runTest {
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
    is CoroutinePuzzleSolutionResult.Failure -> junitFail { toMessage() }
    CoroutinePuzzleSolutionResult.Success -> { /** All OK! */ }
}

private fun CoroutinePuzzleSolutionResult.assertIsNotOk(): CoroutinePuzzleSolutionResult.Failure =
    assertIs<CoroutinePuzzleSolutionResult.Failure> { "Puzzle succeeded unexpectedly" }

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
