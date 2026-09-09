package kmpworkshop.server

import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Each collection is one higher-order call; emissions are awaited callback invocations.
 * Calls made downstream can be expected in a sibling coroutine while an emission is suspended.
 * Downstream termination finishes the current delivery without cancelling the specification;
 * expecting another value from that stopped collector fails the puzzle.
 */
context(_: CoroutinePuzzleBuilderScope)
inline fun <reified A, reified T> CoroutinePuzzleEndPoint<Pair<A, (T) -> Unit>, Unit>.expectingFlowCollector(
): Resource<Resource<Pair<A, suspend (T) -> Unit>>> = createExpectedFlowCollectors(serializer(), serializer())

@PublishedApi
context(builder: CoroutinePuzzleBuilderScope)
internal fun <A, T> CoroutinePuzzleEndPoint<Pair<A, (T) -> Unit>, Unit>.createExpectedFlowCollectors(
    argumentSerializer: KSerializer<A>,
    valueSerializer: KSerializer<T>,
): Resource<Resource<Pair<A, suspend (T) -> Unit>>> = resource { consume ->
    val workerScope = this
    consume(resource { consumeCollector ->
        val matched = CompletableDeferred<Pair<A, suspend (T) -> Unit>>()
        val released = Job()
        val emissions = Mutex()
        var stopped = false
        var owner: Job? = null
        val collection = workerScope.launch {
            try {
                builder.expectCallTo(
                    this@createExpectedFlowCollectors, argumentSerializer, valueSerializer, serializer<Unit>(), serializer<Unit>(),
                ) { argument, callback ->
                    owner = coroutineContext.job
                    matched.complete(argument to callback)
                    released.join()
                }
            } catch (failure: Throwable) {
                matched.completeExceptionally(failure)
                if (failure !is CancellationException) throw failure
            }
        }
        try {
            val (argument, callback) = matched.await()
            consumeCollector(argument to { value ->
                emissions.withLock {
                    if (stopped || owner?.isActive != true) {
                        fail(MoreExpectationsThanSubmissionsFailure(listOf(CoroutinePuzzleExpectedFollowup(
                            id, expectedArgument = Json.encodeToJsonElement(argumentSerializer, argument),
                        ))))
                    }
                    try {
                        callback(value)
                    } catch (cancelled: CancellationException) {
                        stopped = true
                        currentCoroutineContext().ensureActive()
                    } catch (_: ExceptionAcrossRpc) {
                        // Delivery finished exceptionally. Continue evaluating the specification;
                        // a later emission must diagnose the stopped collector, not skip that value.
                        stopped = true
                    }
                }
            })
        } finally {
            released.complete()
            if (!currentCoroutineContext().isActive) collection.cancel()
            withContext(NonCancellable) { collection.join() }
        }
    })
}
