package kmpworkshop.client

import kmpworkshop.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

context(_: CoroutinePuzzleSolutionScope)
inline fun <reified A, reified T> CoroutinePuzzleEndPoint<Pair<A, (T) -> Unit>, Unit>.asFlow(
    argument: A,
): Flow<T> = createCollectorMatchedFlow { callback -> submitCall(argument) { callback(it) } }

context(_: CoroutinePuzzleSolutionScope)
inline fun <reified T> CoroutinePuzzleEndPoint<Pair<Unit, (T) -> Unit>, Unit>.asFlow(): Flow<T> = asFlow(Unit)

@PublishedApi
internal fun <T> createCollectorMatchedFlow(
    submit: suspend (suspend (T) -> Unit) -> Unit,
): Flow<T> = flow {
    coroutineScope {
        val input = Channel<T>()
        val done = Channel<Unit>()
        launch {
            try {
                submit {
                    try {
                        input.send(it)
                        done.receive()
                    } catch (cancelled: CancellationException) {
                        // Delivery runs in the collecting coroutine. Cancelling its callback must cancel that
                        // coroutine too, and cannot finish until downstream cancellation cleanup has returned.
                        this@coroutineScope.cancel(cancelled)
                        withContext(NonCancellable) { done.receiveCatching() }
                        throw cancelled
                    }
                }
            } catch (cancelled: CancellationException) {
                this@coroutineScope.cancel(cancelled)
                throw cancelled
            } finally {
                input.close()
            }
        }

        try {
            for (value in input) {
                emit(value)
                done.send(Unit)
            }
        } finally {
            done.close()
        }
    }
}
