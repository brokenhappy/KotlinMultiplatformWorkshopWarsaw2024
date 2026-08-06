package kmpworkshop.client

import com.woutwerkman.calltreevisualizer.coroutineintegration.CallStackTrackEvent
import com.woutwerkman.calltreevisualizer.coroutineintegration.CallStackTrackEventType
import com.woutwerkman.calltreevisualizer.gui.CallTree
import com.woutwerkman.calltreevisualizer.gui.after
import com.woutwerkman.calltreevisualizer.StackTrackingContext

/** Provides the tracking context required by compiler-instrumented code in normal runs. */
internal object NoOpStackTracker : StackTrackingContext {
    override suspend fun <T> track(functionFqn: String, metadata: ULong, child: suspend () -> T): T = child()
}

internal data class CoroutineDebuggerState(
    val tree: CallTree = CallTree.Empty,
    val lastEvent: String? = null,
)

internal fun CoroutineDebuggerState.after(event: CallStackTrackEvent): CoroutineDebuggerState =
    when (val type = event.eventType) {
        CallStackTrackEventType.CallStackPushType -> copy(
            tree = tree.after(event),
            lastEvent = "Entered ${event.node.functionFqn}",
        )
        CallStackTrackEventType.CallStackPopType -> copy(
            tree = tree.after(event),
            lastEvent = "Returned from ${event.node.functionFqn}",
        )
        is CallStackTrackEventType.CallStackThrowType -> copy(
            tree = tree.after(event),
            lastEvent = "${event.node.functionFqn} threw ${type.throwable.message ?: type.throwable::class.simpleName}",
        )
        CallStackTrackEventType.CallStackCancelled -> copy(
            tree = tree.after(event),
            lastEvent = "Cancelled ${event.node.functionFqn}",
        )
    }
