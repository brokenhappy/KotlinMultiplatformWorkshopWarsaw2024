package kmpworkshop.server

import kmpworkshop.common.importantCleanup
import kmpworkshop.common.updateWithContract
import kmpworkshop.common.withImportantCleanup
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KFunction
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

private class AllCoroutinesDone : Exception()

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
        launch {
            withImportantCleanup {
                var momentOfLastBatch = clock.now()
                try {
                    state.collectLatest { currentState ->
                        if (currentState.activeCoroutineCount == 0) {
                            if (currentState.currentRequests.isEmpty()) throw AllCoroutinesDone()
                            importantCleanup {
                                try {
                                    coroutineScope { batchResumer(context, currentState.currentRequests) }
                                } finally {
                                    if (currentState.currentRequests.any { it.continuation.isActive }) {
                                        println("Some continuations are still active")
                                    }
                                }
                                state.update {
                                    it.copy(currentRequests =
                                        // Fast path for happy path
                                        if (it === currentState) persistentListOf()
                                        // I'm not 100% sure why this fixed [kmpworkshop.server.asd.CoroutinePuzzleUtilitiesTest.trying to call a coroutine puzzle endpoint in parallel while the expectation is synchronous fails]
                                        // Shouldn't we have the assumption that there is no parallelism rn?
                                        else it.currentRequests.removeAll(currentState.currentRequests)
                                    )
                                }
                            }
                            momentOfLastBatch = clock.now()
                            return@collectLatest
                        }
                        clock.delayUntil(momentOfLastBatch + maximumBatchWaitTime)
                        importantCleanup {
                            try {
                                coroutineScope { batchResumer(context, currentState.currentRequests) }
                            } finally {
                                if (currentState.currentRequests.any { it.continuation.isActive }) {
                                    println("Some continuations are still active")
                                }
                            }
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
                } catch (_: AllCoroutinesDone) {
                    // Ignore, we can now finish this coroutine tree
                }
            }
        }
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
    }
}

@OptIn(ExperimentalTime::class)
private suspend fun Clock.delayUntil(wakeup: Instant) {
    delay(wakeup - now())
}

interface BatchedScope<T, R>: CoroutineContext.Element {
    suspend fun callAutoBatched(request: T): R
}

val AlreadyCanceledSymbol = object {}

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

@OptIn(ExperimentalTime::class)
class CoroutineTrackingDispatcherTest {
    @Test
    suspend fun test() {
        val fid = AutoBatchedFunctionId<Int, String> { batchCalls ->
            assertEquals(
                listOf(1, 2, 3),
                batchCalls.map { it.query },
            )
            batchCalls.forEach { request ->
                request.continuation.resume(request.query.toString())
            }
        }

        assertEquals(
            listOf("1", "2", "3"),
            fid.autoBatchedOnQuiescence {
                (1..3).map { async { fid.batched(it) } }.awaitAll()
            },
        )
    }
}

data class User(val id: Int, val neighborIds: List<Int>, val powerLevel: Int)

object UserRepositoryImpl {
    suspend fun get(id: Int): User = userBatchFunction.batched(id)
    suspend fun getMultiple(ids: List<Int>): List<User> = TODO("Optimized single network call to get multiple users")
}

val userBatchFunction = AutoBatchedFunctionId<Int, User> { batch ->
    try {
        UserRepositoryImpl
            .getMultiple(batch.map { it.query })
            .zip(batch.map { it.continuation })
            .forEach { (result, continuation) -> continuation.resume(result) }
    } catch (e: Exception) {
        batch.forEach { it.continuation.resumeWithException(e) }
    }
}

@OptIn(ExperimentalTime::class)
suspend fun mostPowerfulNeighborOf(userId: Int): Unit =
    userBatchFunction.autoBatchedOnQuiescence {
        UserRepositoryImpl
            .get(userId) // Will run batch of size 1
            .neighborIds
            .map { neighborId -> async { UserRepositoryImpl.get(neighborId) } }
            .awaitAll() // Will run batch will all neighbor IDs
            .maxBy { it.powerLevel }
    }