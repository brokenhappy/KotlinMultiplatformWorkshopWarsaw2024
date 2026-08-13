package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleEndPoint
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.Resource
import kmpworkshop.common.ValueOrCompletion
import kmpworkshop.common.WithCallId
import kmpworkshop.common.resource
import kmpworkshop.common.submitCall
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private sealed interface CollectorEvent<out T> {
    data class Register<T>(val collectorId: Long, val destination: SendChannel<ValueOrCompletion<T>>) : CollectorEvent<T>
    data class Response<T>(val response: WithCallId<ValueOrCompletion<T>>) : CollectorEvent<T>
    data class Unregister(val collectorId: Long) : CollectorEvent<Nothing>
}

/** Creates one hot matcher actor whose returned Flow may be collected concurrently. */
context(solutionScope: CoroutinePuzzleSolutionScope)
inline fun <reified A, reified T> CoroutinePuzzleEndPoint<WithCallId<A>, WithCallId<ValueOrCompletion<T>>>.asFlows(
    argument: A,
): Resource<Flow<T>> =
    createCollectorMatchedFlow { collectorId -> submitCall(WithCallId(collectorId, argument)) }

context(solutionScope: CoroutinePuzzleSolutionScope)
inline fun <reified T> CoroutinePuzzleEndPoint<WithCallId<Unit>, WithCallId<ValueOrCompletion<T>>>.asFlows(): Resource<Flow<T>> =
    asFlows(Unit)

@PublishedApi
internal fun <T> createCollectorMatchedFlow(
    submit: suspend (Long) -> WithCallId<ValueOrCompletion<T>>,
): Resource<Flow<T>> = resource { consume ->
    val nextCollectorId = AtomicLong(0)
    val events = Channel<CollectorEvent<T>>(Channel.UNLIMITED)
    val actor = launch {
        val collectors = mutableMapOf<Long, SendChannel<ValueOrCompletion<T>>>()
        for (event in events) {
            when (event) {
                is CollectorEvent.Register -> collectors.put(event.collectorId, event.destination)?.close()
                is CollectorEvent.Response -> collectors[event.response.callId]?.send(event.response.payload)
                is CollectorEvent.Unregister -> collectors.remove(event.collectorId)?.close()
            }
        }
        collectors.values.forEach { it.close() }
    }

    try {
        consume(flow {
            val collectorId = nextCollectorId.incrementAndGet()
            val inbox = Channel<ValueOrCompletion<T>>(Channel.UNLIMITED)
            events.send(CollectorEvent.Register(collectorId, inbox))
            try {
                while (true) {
                    val response = submit(collectorId)
                    events.send(CollectorEvent.Response(response))
                    when (val payload = inbox.receive()) {
                        is ValueOrCompletion.Value -> emit(payload.value)
                        ValueOrCompletion.Completion -> return@flow
                    }
                }
            } finally {
                events.send(CollectorEvent.Unregister(collectorId))
                inbox.close()
            }
        })
    } finally {
        actor.cancel()
    }
}
