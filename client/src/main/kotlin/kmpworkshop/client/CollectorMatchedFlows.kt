package kmpworkshop.client

import io.ktor.utils.io.CancellationException
import kmpworkshop.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

context(_: CoroutinePuzzleSolutionScope)
inline fun <reified A, reified T> CoroutinePuzzleEndPoint<Pair<A, (T) -> Unit>, Unit>.asFlows(
    argument: A,
): Flow<T> = createCollectorMatchedFlow { callback -> submitCall(argument) { callback(it) } }

context(_: CoroutinePuzzleSolutionScope)
inline fun <reified T> CoroutinePuzzleEndPoint<Pair<Unit, (T) -> Unit>, Unit>.asFlows(): Flow<T> = asFlows(Unit)

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
                    input.send(it)
                    done.receive()
                }
            } finally {
                input.close()
            }
        }

        for (value in input) {
            emit(value)
            done.send(Unit)
        }
        done.close()
    }
}
