package kmpworkshop.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private class ImportantCleanupScope(val coroutineScope: CoroutineScope): AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ImportantCleanupScope>
}

suspend fun <T> withImportantCleanup(block: suspend CoroutineScope.() -> T): T = coroutineScope {
    withContext(ImportantCleanupScope(this)) {
        block()
    }
}

/**
 * It's somewhat close to [withContext] ([NonCancellable]), but it will still be canceled
 * when the nearest ancestor's [withImportantCleanup] gets canceled.
 *
 * It's important to note that this function does NOT pass the caller's coroutine context to [block].
 * ```kt
 * withContext(Dispatchers.Default) {
 *     withImportantCleanup {
 *         withContext(Dispatchers.IO) {
 *             importantCleanup {
 *                 doIOWork() // Does NOT run on IO dispatchers, but Dispatchers.Default
 *             }
 *         }
 *     }
 * }
 * ```
 */
suspend fun <T> importantCleanup(block: suspend CoroutineScope.() -> T): T = withContext(NonCancellable) {
    (currentCoroutineContext()[ImportantCleanupScope.Key] ?: error("importantCleanup must be called inside a withImportantCleanup block"))
        .coroutineScope
        .async { block() }
        .await()
}
