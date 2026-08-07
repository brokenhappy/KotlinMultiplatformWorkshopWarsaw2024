package kmpworkshop.client

import com.woutwerkman.calltreevisualizer.coroutineintegration.CallStackTrackEvent
import com.woutwerkman.calltreevisualizer.coroutineintegration.CallStackTrackEventType
import com.woutwerkman.calltreevisualizer.gui.CallTree
import com.woutwerkman.calltreevisualizer.gui.after
import com.woutwerkman.calltreevisualizer.StackTrackingContext
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/** Provides the tracking context required by compiler-instrumented code in normal runs. */
internal object NoOpStackTracker : StackTrackingContext {
    override suspend fun <T> track(functionFqn: String, metadata: ULong, child: suspend () -> T): T = child()
}

internal data class CoroutineDebuggerState(
    val tree: CallTree = CallTree.Empty,
)

internal class CoroutineDebuggerBatchController {
    private val pauseAtNextBatch = AtomicBoolean(false)

    fun resumeUntilNextBatch() {
        pauseAtNextBatch.set(true)
    }

    fun onEmptyBatch(boundaries: Channel<Unit>) {
        if (pauseAtNextBatch.compareAndSet(true, false)) {
            boundaries.trySend(Unit)
        }
    }
}

internal fun CoroutineDebuggerState.after(event: CallStackTrackEvent): CoroutineDebuggerState =
    when (val type = event.eventType) {
        CallStackTrackEventType.CallStackPushType -> copy(
            tree = tree.after(event),
        )
        CallStackTrackEventType.CallStackPopType -> copy(
            tree = tree.after(event),
        )
        is CallStackTrackEventType.CallStackThrowType -> copy(
            tree = tree.after(event),
        )
        CallStackTrackEventType.CallStackCancelled -> copy(
            tree = tree.after(event),
        )
    }
