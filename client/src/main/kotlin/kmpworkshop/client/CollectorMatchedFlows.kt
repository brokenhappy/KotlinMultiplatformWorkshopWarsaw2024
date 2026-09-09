package kmpworkshop.client

import kmpworkshop.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

context(_: CoroutinePuzzleSolutionScope)
inline fun <reified A, reified T> CoroutinePuzzleEndPoint<Pair<A, (T) -> Unit>, Unit>.asFlows(
    argument: A,
): Resource<Flow<T>> = createCollectorMatchedFlow { callback -> submitCall(argument) { callback(it) } }

context(_: CoroutinePuzzleSolutionScope)
inline fun <reified T> CoroutinePuzzleEndPoint<Pair<Unit, (T) -> Unit>, Unit>.asFlows(): Resource<Flow<T>> = asFlows(Unit)

/** Transfers generic callback invocations to the collecting coroutine, preserving Flow's context and ordering. */
@PublishedApi
internal fun <T> createCollectorMatchedFlow(
    submit: suspend (suspend (T) -> Unit) -> Unit,
): Resource<Flow<T>> = resource { consume ->
    consume(flow {
        coroutineScope {
            val emissions = Channel<FlowEmission<T>>()
            val call = launch {
                try {
                    submit { value ->
                        val emission = FlowEmission(value)
                        try {
                            emissions.send(emission)
                            emission.result.await()
                        } catch (failure: Throwable) {
                            emission.cancel(failure)
                            withContext(NonCancellable) { emission.result.join() }
                            throw failure
                        }
                    }
                } finally {
                    emissions.close()
                }
            }
            try {
                for (emission in emissions) {
                    try {
                        coroutineScope {
                            if (emission.attach(this)) {
                                ensureActive()
                                emit(emission.value)
                            }
                        }
                        emission.result.complete(Unit)
                    } catch (failure: Throwable) {
                        emission.result.completeExceptionally(failure)
                        throw failure
                    }
                }
            } finally {
                withContext(NonCancellable) { call.cancelAndJoin() }
            }
        }
    })
}

private class FlowEmission<T>(val value: T) {
    val result = CompletableDeferred<Unit>()
    private var scope: CoroutineScope? = null

    @Synchronized fun attach(scope: CoroutineScope): Boolean {
        if (result.isCompleted) return false
        this.scope = scope
        return true
    }

    @Synchronized fun cancel(failure: Throwable) {
        val running = scope
        if (running == null) result.completeExceptionally(failure)
        else running.cancel(failure as? CancellationException ?: CancellationException("Emission cancelled", failure))
    }
}
