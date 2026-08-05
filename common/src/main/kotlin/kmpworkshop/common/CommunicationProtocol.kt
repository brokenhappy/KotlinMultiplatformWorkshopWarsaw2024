package kmpworkshop.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * Bidirectional communication used by a puzzle client.
 *
 * [outgoing] carries messages from the puzzle implementation to its client; [incoming] carries the client's replies
 * back to the implementation. A [Resource] owns the channels so every solve attempt gets an isolated lifecycle.
 */
data class CommunicationProtocol<Outgoing, Incoming>(
    val outgoing: ReceiveChannel<Outgoing>,
    val incoming: SendChannel<Incoming>,
)

/** Builds a channel-backed communication protocol. */
fun <Outgoing, Incoming> communicationProtocol(
    underlyingComms: suspend CoroutineScope.(
        outgoing: SendChannel<Outgoing>,
        incoming: ReceiveChannel<Incoming>,
    ) -> Unit,
): Resource<CommunicationProtocol<Outgoing, Incoming>> = resource { consume ->
    val outgoing = Channel<Outgoing>(64)
    val incoming = Channel<Incoming>(64)
    try {
        launch { underlyingComms(outgoing, incoming) }
        consume(CommunicationProtocol(outgoing, incoming))
    } finally {
        incoming.close()
        outgoing.close()
    }
}

/**
 * Adapts a flow-based transport to a channel-based [CommunicationProtocol].
 *
 * The transport receives the client's incoming messages as a [Flow] and returns its outgoing messages as a [Flow].
 */
fun <Outgoing, Incoming> mapFlowsToCommunicationProtocol(
    transport: (Flow<Incoming>) -> Flow<Outgoing>,
): Resource<CommunicationProtocol<Outgoing, Incoming>> = communicationProtocol { outgoing, incoming ->
    try {
        transport(incoming.consumeAsFlow()).collect { outgoing.send(it) }
    } finally {
        outgoing.close()
    }
}

/**
 * Connects an incoming [Flow] to this protocol and exposes its outgoing messages as a [Flow].
 *
 * The resource owns the channel lifetime: when either side finishes, the opposite channel is closed after its work
 * has had a chance to complete.
 */
fun <Outgoing, Incoming> Resource<CommunicationProtocol<Outgoing, Incoming>>.communicateAsFlows(
    incomingMessages: Flow<Incoming>,
): Flow<Outgoing> = channelFlow {
    this@communicateAsFlows.use { (outgoing, incoming) ->
        launch {
            try {
                incomingMessages.collect { incoming.send(it) }
            } finally {
                incoming.close()
            }
        }
        for (message in outgoing) send(message)
    }
}
