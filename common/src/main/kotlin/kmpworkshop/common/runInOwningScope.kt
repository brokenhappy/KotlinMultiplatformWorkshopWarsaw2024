package kmpworkshop.common

import kotlinx.coroutines.*

/** Transfers work to [owner], while cancellation of the caller still cancels and joins that work. */
suspend fun <T> runInOwningScope(owner: CoroutineScope, block: suspend CoroutineScope.() -> T): T {
    val result = CompletableDeferred<Result<T>>(owner.coroutineContext.job)
    owner.launch(context = result) {
        result.complete(runCatching { coroutineScope { block() } })
    }.invokeOnCompletion { failure ->
        if (failure != null) result.complete(Result.failure(failure))
    }
    try {
        result.join()
    } catch (failure: Throwable) {
        result.cancel("Caller cancelled", failure)
        withContext(NonCancellable) { result.join() }
        throw failure
    }
    return result.await().getOrThrow()
}
