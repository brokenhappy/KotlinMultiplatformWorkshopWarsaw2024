package kmpworkshop.server

import kmpworkshop.common.AutoBatchedFunctionId
import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.CoroutinePuzzleEndPoint
import kmpworkshop.common.CoroutinePuzzleEndPointWaitingState
import kmpworkshop.common.CoroutinePuzzleFailedControlFlowException
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleState
import kmpworkshop.common.autoBatchedOnQuiescence
import kmpworkshop.common.completeWithResultOf
import kmpworkshop.common.withInterceptingDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.ExperimentalTime

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
    builder: suspend context(CoroutinePuzzleBuilderScope) CoroutineScope.() -> Unit,
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
                // Identity-based (not descriptor-based): the same endpoint can be expected concurrently more than
                // once (e.g. via multiple `launch { endpoint.expectCall { ... } }`), and those share one descriptor.
                // A descriptor-based set can't tell which of those identical-looking instances actually matched.
                .toSet()
            // All expectation calls that did not have a matching submission need to repeat
            batch.forEach { it.continuation.resume(it.query !in callsThatDidntHaveMatchingSubmit) }
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
            return withContext(InStrictParallelismExpectation) { block() }
        }
    }) {
        try {
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
        } finally {
            stateFlow.value = CoroutinePuzzleState.ExpectationsDone
        }
    }
}

context(_: CoroutinePuzzleBuilderScope)
fun fail(reason: CoroutinePuzzleSolutionResult.Failure.Reason): Nothing = throw CoroutinePuzzleFailedControlFlowException(reason)

context(_: CoroutinePuzzleBuilderScope)
fun fail(message: String): Nothing = fail(CoroutinePuzzleSolutionResult.Failure.Reason.Custom(message))

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
