package kmpworkshop.common

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A version of [AutoBatchedFunctionId] without context */
fun <T, R> AutoBatchedFunctionId(
    /**
     * If somehow at some point [AutoBatchedFunctionId.batched] is called outside an [autoBatchedOnQuiescence] scope,
     * this function will be called instead. By default, it throws an error.
     */
    fallbackOutOfBatchScope: suspend CoroutineScope.(T) -> R = {
        error("batched function calls MUST run on an autoBatchedOnCoroutineDeadlocks scope")
    },
    /**
     * The [key] is useful if you want to have fine-grained control over how multiple different types of batching work
     * across a code base. By default, each [AutoBatchedFunctionId] has its own key.
     */
    key: CoroutineContext.Key<BatchedScope<T, R>> = object : CoroutineContext.Key<BatchedScope<T, R>> {},
    batchResumer: suspend CoroutineScope.(batch: List<SuspendedBatchCall<T, R>>) -> Unit,
): AutoBatchedFunctionId<T, Unit, R> = AutoBatchedFunctionId(fallbackOutOfBatchScope, key) { _, it -> batchResumer(it) }

/**
 * See [autoBatchedOnQuiescence]
 *
 * This is a unique identifier for a function that is automatically batched.
 *
 * The [batchResumer] is what is called every time a batch of [batched] calls is intended to be resumed.
 *
 * [C] is a context value that is passed from [autoBatchedOnQuiescence] to [batched] functions.
 * If this doesn't sound useful, set it to [Unit].
 */
class AutoBatchedFunctionId<T, C, R>(
    /**
     * If somehow at some point [batched] is called outside of an [autoBatchedOnQuiescence] scope,
     * this function will be called instead. By default it throws an error.
     */
    private val fallbackOutOfBatchScope: suspend CoroutineScope.(T) -> R = {
        error("batched function calls MUST run on an autoBatchedOnCoroutineDeadlocks scope")
    },
    /**
     * The [key] is useful if you want to have fine-grained control over how multiple different types of batching work
     * across a code base. By default, each [AutoBatchedFunctionId] has its own key.
     * You can improve the toString of this function id by overriding the [toString].
     */
    internal val key: CoroutineContext.Key<BatchedScope<T, R>> = object : CoroutineContext.Key<BatchedScope<T, R>> {},
    /**
     * The function that is called when a batch is ready to be processed.
     * This function:
     *  - MUST complete all continuations in the batch.
     *  - COULD complete those continuations asynchronously.
     *
     * [batchResumer] calls will never run concurrently to each other.
     * Meaning that batch resumptions block all batched calls.
     * That's why you could run [batchResumer] asynchronously.
     */
    internal val batchResumer: suspend CoroutineScope.(context: C, batch: List<SuspendedBatchCall<T, R>>) -> Unit,
) {
    /**
     * This function will become part of the current batch and suspend until the batch is resumed by the [batchResumer].
     */
    suspend fun batched(request: T): R {
        val scope = currentCoroutineContext()[key]
        return if (scope == null) coroutineScope { fallbackOutOfBatchScope(request) }
        else scope.callAutoBatched(request)
    }

    override fun toString(): String = "AutoBatchedFunctionId(key=$key)"
}

/** A version of [autoBatchedOnQuiescence] without context */
@OptIn(ExperimentalTime::class)
suspend fun <U, T, R> AutoBatchedFunctionId<T, Unit, R>.autoBatchedOnQuiescence(
    maximumBatchWaitTime: Duration = Duration.INFINITE,
    clock: Clock = Clock.System,
    block: suspend CoroutineScope.() -> U,
): U = autoBatchedOnQuiescence(Unit, maximumBatchWaitTime, clock, block)

/**
 * Ensures all calls of [this].[batched](AutoBatchedFunctionId.batched) inside [block] are batched.
 *
 * All calls of [this].[batched](AutoBatchedFunctionId.batched) will become part of the batch and suspend
 * until the batch is resumed. Resuming a batch resumes all suspended [this].[batched](AutoBatchedFunctionId.batched)
 * calls with a single [this].[batchResumer](AutoBatchedFunctionId.batchResumer) call.
 *
 * A batch is resumed when either:
 *  - All coroutines under [block] are suspended.
 *  - Or the last batch was resumed [maximumBatchWaitTime] ago according to [clock].
 *
 * This only works when:
 *  - You preserve structured concurrency within [block], no guarantees are given otherwise.
 *  - You do not switch [Dispatchers] inside [block]. But this function preserves the outer [Dispatchers]' behavior.
 *
 * Example:
 * ```kt
 * object UserRepositoryImpl {
 *     suspend fun get(id: Int): User = userBatchFunction.batched(id)
 *     suspend fun getMultiple(ids: List<Int>): List<User> = ... // Optimized single network call to get multiple users
 * }
 *
 * val userBatchFunction = AutoBatchedFunctionId<Int, User> { batch ->
 *     // In here you will handle a batch to resume all suspended `batched` calls.
 * }
 *
 * suspend fun mostPowerfulNeighborOf(userId: Int): Unit =
 *     userBatchFunction.autoBatchedOnCoroutineDeadlocks {
 *         UserRepositoryImpl
 *             .get(userId) // Will run a batch of size = 1
 *             .neighborIds
 *             .map { neighborId -> async { UserRepositoryImpl.get(neighborId) } }
 *             .awaitAll() // Will run batch will all neighbor IDs
 *             .maxBy { it.powerLevel }
 *     }
 * ```
 *
 */
suspend fun <U, T, C, R> AutoBatchedFunctionId<T, C, R>.autoBatchedOnQuiescence(
    context: C,
    maximumBatchWaitTime: Duration = Duration.INFINITE,
    @OptIn(ExperimentalTime::class)
    clock: Clock = Clock.System,
    block: suspend CoroutineScope.() -> U,
): U {
    data class StateOfCoroutines(
        val activeCoroutineCount: Int,
        val currentRequests: PersistentList<SuspendedBatchCall<T, R>>,
    )

    val state = MutableStateFlow(StateOfCoroutines(activeCoroutineCount = 0, currentRequests = persistentListOf()))
    @OptIn(ExperimentalTime::class)
    return coroutineScope {
        val trackingJob = launch {
            withImportantCleanup {
                var momentOfLastBatch = clock.now()
                state.collectLatest { currentState ->
                    if (currentState.activeCoroutineCount == 0) {
                        if (currentState.currentRequests.isEmpty()) {
                            // Nothing to do *right now*, but that doesn't mean [block] is done - it may just be
                            // between two batches (e.g. waiting for the next network call to arrive). Whether we're
                            // truly done is decided by [block] actually completing, below, which cancels this
                            // tracking coroutine - not by momentarily observing nothing pending.
                            return@collectLatest
                        }
                        importantCleanup {
                            var claimedRequests: PersistentList<SuspendedBatchCall<T, R>>
                            state.updateWithContract { latest ->
                                claimedRequests = latest.currentRequests
                                latest.copy(currentRequests = persistentListOf())
                            }
                            if (claimedRequests.isNotEmpty())
                                coroutineScope { batchResumer(context, claimedRequests) }
                        }
                        momentOfLastBatch = clock.now()
                        return@collectLatest
                    }
                    clock.delayUntil(momentOfLastBatch + maximumBatchWaitTime)
                    importantCleanup {
                        coroutineScope { batchResumer(context, currentState.currentRequests) }
                        var processedContinuations: Set<CancellableContinuation<R>>? = null
                        // We just processed the batch while other coroutines were still running
                        // That means that new batch calls might have been made...
                        state.update { old ->
                            // ... Therefore, we first check whether any requests have been made since out last request...
                            if (old.currentRequests === currentState.currentRequests) {
                                // ... If no requests have been made, we can simply set to an empty list.
                                // This is an optimization for the most likely case.
                                old.copy(currentRequests = persistentListOf())
                            } else {
                                // ... Only if another request has been made we remove only continuations that we completed
                                processedContinuations = processedContinuations
                                    ?: old.currentRequests.mapTo(HashSet()) { it.continuation }
                                old.copy(
                                    currentRequests = old
                                        .currentRequests
                                        .filter { it.continuation !in processedContinuations }
                                        .toPersistentList(),
                                )
                            }
                        }
                    }
                    momentOfLastBatch = clock.now()
                }
            }
        }
        try {
            withInterceptingDispatcher(
                onDispatchScheduled = {
                    state.update { it.copy(activeCoroutineCount = it.activeCoroutineCount + 1) }
                },
                onDispatchedRunnableComplete = {
                    state.update { it.copy(activeCoroutineCount = it.activeCoroutineCount - 1) }
                },
            ) {
                withContext(
                    object : BatchedScope<T, R> {
                        override suspend fun callAutoBatched(request: T): R = suspendCancellableCoroutine { continuation ->
                            val batchCall = SuspendedBatchCall(request, continuation)
                            state.update {
                                it.copy(currentRequests = it.currentRequests.add(batchCall))
                            }
                            continuation.invokeOnCancellation {
                                state.update { // Prevents memory leak
                                    // TODO: Worth optimizing data structure to remove this O(N)?
                                    it.copy(currentRequests = it.currentRequests.remove(batchCall))
                                }
                                batchCall.invokeCancellationHandler()
                            }
                        }

                        override val key: CoroutineContext.Key<*> = this@autoBatchedOnQuiescence.key
                    },
                    block,
                )
            }
        } finally {
            // block (and, transitively, everything spawned from it) has now fully completed - there can be no more
            // pending requests, since any of those would still be suspended as part of block's own coroutine tree,
            // which would have kept it from completing. It's now safe to stop watching for batches to resume.
            trackingJob.cancel()
        }
    }
}

@OptIn(ExperimentalTime::class)
private suspend fun Clock.delayUntil(wakeup: Instant) {
    delay(wakeup - now())
}

interface BatchedScope<T, R>: CoroutineContext.Element {
    suspend fun callAutoBatched(request: T): R
}

private val AlreadyCanceledSymbol = object {}

class SuspendedBatchCall<T, R>(val query: T, val continuation: CancellableContinuation<R>) {
    private var cancellationHandler = AtomicReference<Any?>(null)
    fun invokeOnCancellation(block: () -> Unit) {
        val new = cancellationHandler.updateAndGet { old ->
            when {
                old === AlreadyCanceledSymbol -> AlreadyCanceledSymbol
                old === null -> block
                else -> throw IllegalStateException("Already has a cancellation handler")
            }
        }
        if (new === AlreadyCanceledSymbol) {
            block()
        }
    }

    fun invokeCancellationHandler() {
        val old = cancellationHandler.getAndUpdate { old ->
            when {
                old === AlreadyCanceledSymbol -> throw IllegalStateException("Already got cancelled")
                else -> AlreadyCanceledSymbol
            }
        }
        @Suppress("UNCHECKED_CAST")
        if (old !== null && old !== AlreadyCanceledSymbol) (old as () -> Unit).invoke()
    }
}
