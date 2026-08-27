package kmpworkshop.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Is a MUST-USE around all blocking calls in suspend code.
 * This prevents the coroutine's thread pool from exhaustion.
 */
suspend fun <T> coroutinesToLoom(block: () -> T): T {
    val started = AtomicBoolean(false)
    val result = CompletableDeferred<T>()
    val context = currentCoroutineContext()

    val thread = Thread.ofVirtual().start {
        if (started.compareAndSet(false, true)) result.completeWith(runCatching { block() })
    }
    return try {
        result.await()
    } catch (c: CancellationException) {
        if (!context.isActive && !started.compareAndSet(false, true)) {
            thread.interrupt()
            withContext(NonCancellable) {
                try {
                    result.await()
                } catch (interruptedExceptionWeDontWantInCoroutines: InterruptedException) {
                    try {
                        context.ensureActive()
                    } catch (innerCE: CancellationException) {
                        innerCE.addSuppressed(interruptedExceptionWeDontWantInCoroutines)
                        throw innerCE
                    }
                } catch (t: Throwable) {
                    if (c !== t) c.suppressed.forEach { t.addSuppressed(it) }
                    throw t
                }
            }
        }
        throw c
    }
}