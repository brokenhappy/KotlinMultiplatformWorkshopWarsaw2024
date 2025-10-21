package kmpworkshop.server

import kmpworkshop.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentLinkedDeque

interface CoroutinePuzzleBuilderScope {
    suspend fun <T, R> expectCallTo(
        endPoint: CoroutinePuzzleEndPoint<T, R>,
        tSerializer: KSerializer<T>,
        rSerializer: KSerializer<R>,
        valueProducer: suspend (T) -> R,
    ): T

    /** Schedules [branch] asynchronously on this [CoroutinePuzzleBuilderScope] */
    fun launchBranch(branch: suspend context(CoroutinePuzzleBuilderScope) () -> Unit): Job
    suspend fun <T> puzzleScope(branch: suspend context(CoroutinePuzzleBuilderScope) () -> T): T
}

fun coroutinePuzzle(
    builder: suspend context(CoroutinePuzzleBuilderScope) () -> Unit,
): CoroutinePuzzle = CoroutinePuzzle { stateFlow ->
    puzzleScope(builder, stateFlow, isTopLevel = true)
}

private suspend fun <T> puzzleScope(
    branch: suspend context(CoroutinePuzzleBuilderScope) () -> T,
    stateFlow: MutableStateFlow<CoroutinePuzzleState>,
    isTopLevel: Boolean = false,
): T = coroutineScope {
    context(object : CoroutinePuzzleBuilderScope {
        override suspend fun <T, R> expectCallTo(
            endPoint: CoroutinePuzzleEndPoint<T, R>,
            tSerializer: KSerializer<T>,
            rSerializer: KSerializer<R>,
            valueProducer: suspend (T) -> R
        ): T {
            val argumentDeferred = CompletableDeferred<T>()
            val resultDeferred = CompletableDeferred<R>()
            val state = CoroutinePuzzleEndPointWaitingState(
                endPoint = endPoint,
                isTaken = false,
                submitCall = { givenValue ->
                    // This function is being called from the submission side, our context is unknown here.
                    // So we only do `complete` and `await`, and let the work be done on the "expectCall" side
                    argumentDeferred.complete(Json.decodeFromJsonElement(tSerializer, givenValue))
                    Json.encodeToJsonElement(rSerializer, resultDeferred.await())
                },
            )
            stateFlow.update { old ->
                old.copy(expectedCalls = old.expectedCalls + state)
            }
            return runCatching { argumentDeferred.await() }
                .also { _ ->
                    // First (and always) clean up state, to make sure all side effects are done when the functions end.
                    stateFlow.update { old -> old.copy(expectedCalls = old.expectedCalls - state) }
                }
                .getOrThrow()
                .also { argument ->
                    try {
                        resultDeferred.complete(valueProducer(argument)) // Try to produce value on "expectCall" side
                    } catch (failedException: CoroutinePuzzleFailedControlFlowException) {
                        // Don't complete resultDeferred.
                        // Just let the solving side wait, since they will get canceled shortly.
                        throw failedException
                    } catch (t: Throwable) {
                        resultDeferred.completeExceptionally(t)
                    }
                }
        }

        override fun launchBranch(branch: suspend context(CoroutinePuzzleBuilderScope) () -> Unit): Job {
            stateFlow.update { old ->
                old.copy(branchCount = old.branchCount + 1)
            }
            return this@coroutineScope.launch {
                try {
                    puzzleScope(branch, stateFlow)
                } finally {
                    stateFlow.update { old ->
                        old.copy(branchCount = old.branchCount - 1)
                    }
                }
            }
        }

        override suspend fun <T> puzzleScope(branch: suspend context(CoroutinePuzzleBuilderScope) () -> T): T =
            puzzleScope(branch, stateFlow)
    }) {
        branch()
    }
}.also {
    if (isTopLevel) stateFlow.update { it.copy(branchCount = 0) }
}

context(_: CoroutinePuzzleBuilderScope)
suspend fun launchBranches(
    parallelism: Int,
    parallelExpectations: suspend context(CoroutinePuzzleBuilderScope) () -> Unit,
) {
    (0..<parallelism).toList().branchForEach { parallelExpectations() }
}

context(_: CoroutinePuzzleBuilderScope)
suspend fun <T, R> List<T>.branchForEach(
    parallelExpectations: suspend context(CoroutinePuzzleBuilderScope) (T) -> R,
): List<R> {
    val concurrentList = ConcurrentLinkedDeque<R & Any>()
    puzzleScope {
        this.dropLast(1).forEach { element ->
            launchBranch {
                concurrentList.add(parallelExpectations(element))
            }
        }
        concurrentList.add(parallelExpectations(this.last()))
    }
    return concurrentList.toList()
}

context(_: CoroutinePuzzleBuilderScope)
internal fun fail(message: String): Nothing = throw CoroutinePuzzleFailedControlFlowException(message, true)


context(builder: CoroutinePuzzleBuilderScope)
internal suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint<T, R>.expectCall(
    noinline valueProducer: suspend (T) -> R,
): T = builder.expectCallTo(this, serializer(), serializer(), valueProducer)

context(builder: CoroutinePuzzleBuilderScope)
internal inline fun verify(condition: Boolean, message: () -> String) {
    if (!condition) fail(message())
}
context(builder: CoroutinePuzzleBuilderScope)
internal inline fun <T : Any> T?.verifyNotNull(message: () -> String): T = this ?: fail(message())

context(builder: CoroutinePuzzleBuilderScope)
internal suspend inline fun <
    /* @OnlyInputTypes */ reified T,
    /* @OnlyInputTypes */ reified R,
> CoroutinePuzzleEndPoint<R, T>.expectCall(value: T): R = expectCall { value }

/** Schedules [branch] asynchronously on this [CoroutinePuzzleBuilderScope] */
context(scope: CoroutinePuzzleBuilderScope)
internal fun launchBranch(branch: suspend context(CoroutinePuzzleBuilderScope) () -> Unit): Job =
    scope.launchBranch(branch)

/** Close to what a `coroutineScope` is, but it exposed [launchBranch] instead of [kotlinx.coroutines.CoroutineScope].launch */
context(scope: CoroutinePuzzleBuilderScope)
internal suspend fun <T> puzzleScope(branch: suspend context(CoroutinePuzzleBuilderScope) () -> T): T =
    // Problem is that if main branch completes before launched branch, we don't decrease the branch counter...
    scope.puzzleScope(branch)
