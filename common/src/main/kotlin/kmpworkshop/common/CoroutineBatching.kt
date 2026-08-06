@file:OptIn(ExperimentalAtomicApi::class)

package kmpworkshop.common

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.CoroutineContext.Key
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Keeps the quiescence tracker alive while a coroutine is suspended waiting for an external coroutine. */
public suspend fun <T> assumeNotQuiescent(block: suspend () -> T): T {
    val tracker = currentCoroutineContext()[QuiescenceTracker]
        ?: return block()
    tracker.enterNonQuiescentSection()
    return try {
        block()
    } finally {
        tracker.exitNonQuiescentSection()
    }
}

private interface QuiescenceTracker : CoroutineContext.Element {
    fun enterNonQuiescentSection()
    fun exitNonQuiescentSection()

    companion object Key : CoroutineContext.Key<QuiescenceTracker>
    override val key: CoroutineContext.Key<*> get() = Key
}

/**
 * See [autoBatchedOnQuiescence]
 *
 * This is a unique identifier for a function that is automatically batched.
 *
 * The [batchResumer] is what is called every time a batch of [batched] calls is intended to be resumed.
 *
 */
class AutoBatchedFunctionId<T, R>(
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
    internal val key: Key<BatchedScope<T, R>> = object : Key<BatchedScope<T, R>> {},
    /**
     * The function that is called when a batch is ready to be processed.
     * This function:
     *  - MUST complete all continuations in the batch.
     *  - COULD complete those continuations asynchronously.
     *
     * [batchResumer] calls will never run concurrently to each other.
     * Meaning that batch resumptions block all batched calls.
     * That's why you could run [batchResumer] asynchronously.
     *
     * A batch resumption could happen with an empty batch. This means the coroutine tree under
     * the batch resumer is quiescent, but no calls were made in the batch.
     * This means one of:
     *  - The coroutine tree is fully suspended and will never resume.
     *  - Or one of the following cases that should be avoided:
     *    - The [Dispatcher](kotlinx.coroutines.CoroutineDispatcher) was
     *      switched (through `withContext(Dispatchers.IO) {}` for example)
     *    - At least one of the coroutines is waiting to be resumed by a coroutine outside the coroutine tree.
     */
    internal val batchResumer: suspend CoroutineScope.(batch: List<SuspendedBatchCall<T, R>>) -> Unit,
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
suspend fun <U, T, R> AutoBatchedFunctionId<T, R>.autoBatchedOnQuiescence(
    maximumBatchWaitTime: Duration = Duration.INFINITE,
    @OptIn(ExperimentalTime::class)
    clock: Clock = Clock.System,
    block: suspend CoroutineScope.() -> U,
): U {
    data class StateOfCoroutines(
        val activeCoroutineCount: Int,
        val currentRequests: PersistentList<SuspendedBatchCall<T, R>>,
        val expectingZeroActiveCountBecauseWeJustClearedRequests: Boolean = false,
    )

    val isComplete = AtomicBoolean(false)
    val state = MutableStateFlow(StateOfCoroutines(activeCoroutineCount = 0, currentRequests = persistentListOf()))
    @OptIn(ExperimentalTime::class)
    return withLaunched(taskThatMustOutliveUsage = {
        // Keep publication independent from the collectLatest flush body: that body can intentionally remain inside
        // importantCleanup while a batch resumer waits for its peer, but raw-idle transitions must still be visible.
        withImportantCleanup {
            var momentOfLastBatch = clock.now()
            state.collectLatest { currentState ->
                if (isComplete.load()) return@collectLatest
                if (currentState.activeCoroutineCount == 0 && !currentState.expectingZeroActiveCountBecauseWeJustClearedRequests) {
                    importantCleanup {
                        var claimedRequests: PersistentList<SuspendedBatchCall<T, R>>
                        state.updateWithContract { latest ->
                            claimedRequests = latest.currentRequests
                            latest.copy(currentRequests = persistentListOf(), expectingZeroActiveCountBecauseWeJustClearedRequests = true)
                        }
                        batchResumer(claimedRequests) // No wrapping in coroutineScope because the local scope isn't used above. Risky micro optimization
                    }
                    momentOfLastBatch = clock.now()
                    return@collectLatest
                }
                clock.delayUntil(momentOfLastBatch + maximumBatchWaitTime)
                importantCleanup {
                    coroutineScope { batchResumer(currentState.currentRequests) }
                    var processedContinuations: Set<GuardedContinuation<R>>? = null
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
                                ?: currentState.currentRequests.mapTo(HashSet()) { it.continuation }
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
    }) {
        withInterceptingDispatcher(
            onDispatchScheduled = {
                state.update {
                    it.copy(
                        activeCoroutineCount = it.activeCoroutineCount + 1,
                        expectingZeroActiveCountBecauseWeJustClearedRequests = false
                    )
                }
            },
            onDispatchedRunnableComplete = {
                state.update { it.copy(activeCoroutineCount = it.activeCoroutineCount - 1) }
            },
        ) {
            val batchedScope = object : BatchedScope<T, R> {
                    override suspend fun callAutoBatched(request: T): R =
                        suspendCancellableCoroutine { continuation ->
                            val guardedContinuation =
                                continuation.ensuringItsResumedOn(this@withInterceptingDispatcher)
                            val batchCall = SuspendedBatchCall(request, guardedContinuation)
                            state.update {
                                it.copy(currentRequests = it.currentRequests.add(batchCall))
                            }
                            continuation.invokeOnCancellation {
                                state.update { // Prevents memory leak
                                    // TODO: Worth optimizing data structure to remove this O(N)?
                                    it.copy(currentRequests = it.currentRequests.remove(batchCall))
                                }
                                guardedContinuation.invokeCancellationHandler()
                            }
                        }

                    override val key: Key<*> = this@autoBatchedOnQuiescence.key
                }
            val quiescenceTracker = object : QuiescenceTracker {
                    override fun enterNonQuiescentSection() {
                        state.update {
                            it.copy(
                                activeCoroutineCount = it.activeCoroutineCount + 1,
                                expectingZeroActiveCountBecauseWeJustClearedRequests = false,
                            )
                        }
                    }

                    override fun exitNonQuiescentSection() {
                        state.update { it.copy(activeCoroutineCount = it.activeCoroutineCount - 1) }
                    }
                }
            withContext(batchedScope + quiescenceTracker, block).also { isComplete.store(true) }
        }
    }
}

private fun <R> CancellableContinuation<R>.ensuringItsResumedOn(coroutineScope: CoroutineScope): GuardedContinuation<R> =
    object : GuardedContinuation<R> {
        context(_: QuiescenceTrackedScope)
        override fun resumeWith(result: Result<R>) {
            this@ensuringItsResumedOn.resumeWith(result)
        }

        override suspend fun <T> runOnScopeThatTracksQuiescence(
            block: suspend context(QuiescenceTrackedScope) CoroutineScope.() -> T
        ): T = context(QuiescenceTrackedScopeImpl) {
            coroutineScope.async { block() }.await()
        }

        private var cancellationHandler = AtomicReference<Any?>(null)
        override fun invokeOnCancellation(block: () -> Unit) {
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

        override fun invokeCancellationHandler() {
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

suspend fun <T> withLaunched(
    taskThatMustOutliveUsage: suspend CoroutineScope.() -> Unit,
    usage: suspend CoroutineScope.() -> T,
): T = coroutineScope {
    val untrackedJob = CoroutineScope(coroutineContext.minusKey(Job)).async {
        taskThatMustOutliveUsage()
    }

    launch(start = UNDISPATCHED) {
        withContext(NonCancellable) {
            untrackedJob.await()
        }
    }

    try {
        coroutineScope { usage() }
    } finally {
        untrackedJob.cancel()
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

/**
 * Just a regular [CancellableContinuation].
 * But it prevents you from making a common race condition when using [AutoBatchedFunctionId] API.
 * Rule of thumb, if you want to resume a bunch of continuations that came from a batch resumer,
 * wrap all continuation resumptions in a [runOnScopeThatTracksQuiescence] function.
 *
 * TODO: Explain
 *
 * // You would expect that the batch resumed can only trigger twice here. But it could trigger twice actually
 * val function = AutoBatchedFunctionId(batchResumer = {
 *     println(it.size) // Should print: 2, 2. However, it could print [2, 1, 1]
 *     it.forEach { it.continuation.resume(Unit) }
 * })
 *
 * function.autoBatchedOnQuiescence {
 *     repeat(2) {
 *         launch {
 *             function.batched(Unit)
 *             function.batched(Unit)
 *         }
 *     }
 * }
 */
interface GuardedContinuation<in R> {
    context(_: QuiescenceTrackedScope)
    fun resumeWith(result: Result<R>)
    suspend fun <T> runOnScopeThatTracksQuiescence(
        block: suspend context(QuiescenceTrackedScope) CoroutineScope.() -> T,
    ): T
    fun invokeOnCancellation(block: () -> Unit)
    fun invokeCancellationHandler()
}

context(_: QuiescenceTrackedScope)
fun <R> GuardedContinuation<R>.resume(result: R): Unit = resumeWith(Result.success(result))

sealed interface QuiescenceTrackedScope
private data object QuiescenceTrackedScopeImpl: QuiescenceTrackedScope

class SuspendedBatchCall<T, in R>(val query: T, val continuation: GuardedContinuation<R>)

suspend fun <T, R> Iterable<SuspendedBatchCall<T, R>>.resumeAllQuiescentTrackedScope(
    block: suspend context(QuiescenceTrackedScope) CoroutineScope.(SuspendedBatchCall<T, R>) -> Unit,
) {
    resumeAllQuiescentTrackedScope({ it.continuation }, block)
}

suspend fun <T> Iterable<T>.resumeAllQuiescentTrackedScope(
    mapper: (T) -> GuardedContinuation<*>,
    block: suspend context(QuiescenceTrackedScope) CoroutineScope.(T) -> Unit
) {
    val iterator = iterator()
    if (!iterator.hasNext()) return
    val first = iterator.next()
    mapper(first).runOnScopeThatTracksQuiescence {
        block(first)
        while (iterator.hasNext()) block(iterator.next())
    }
}
