package kmpworkshop.common

import kmpworkshop.common.CoroutinePuzzleCallPayload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

typealias CoroutinePuzzleProtocol =
    CommunicationProtocol<CoroutinePuzzleExpectationBatchOrCompletion, CoroutinePuzzleBatch<CoroutinePuzzleSubmissionPayload>>

sealed class CoroutinePuzzleHistoryBatch {
    abstract val entries: List<WithCallId<CoroutinePuzzleCallPayload<*>>>
    data class Submission(override val entries: List<WithCallId<CoroutinePuzzleSubmissionPayload>>) : CoroutinePuzzleHistoryBatch()
    data class Expectation(override val entries: List<WithCallId<CoroutinePuzzleExpectationPayload>>) : CoroutinePuzzleHistoryBatch()
}

suspend fun Resource<CoroutinePuzzleProtocol>.solve(solution: CoroutinePuzzleSolution): CoroutinePuzzleResultWithHistory =
    asPuzzle().solve(solution)

fun Resource<CoroutinePuzzleProtocol>.asPuzzle(): CoroutinePuzzle = CoroutinePuzzle { solution ->
    channelFlow {
        this@asPuzzle.use { (expectations, submissions) ->
            val pending = ConcurrentHashMap<Long, GuardedContinuation<JsonElement>>()
            val active = ConcurrentHashMap<Long, LocalPuzzleCall>()
            val outbox = mutableListOf<WithCallId<CoroutinePuzzleSubmissionPayload>>()
            suspend fun flush() {
                val batch = outbox.toList()
                outbox.clear()
                send(CoroutinePuzzleSolveState.Running(CoroutinePuzzleHistoryBatch.Submission(batch)))
                submissions.send(batch)
            }
            val batching = AutoBatchedFunctionId<WithCallId<CoroutinePuzzleSubmissionPayload>, JsonElement>(
                fallbackOutOfBatchScope = {
                    error("You broke structured concurrency. Endpoint calls must stay inside the solution's coroutine scope.")
                },
                batchResumer = { batch ->
                    outbox += batch.map { it.query }
                    val (notifications, requests) = batch.partition { it.query.payload is Outcome }
                    requests.forEach { pending[it.query.callId] = it.continuation }
                    if (notifications.isEmpty()) flush()
                    else notifications.resumeAllQuiescentTrackedScope { it.continuation.resume(JsonNull) }
                },
            )
            withLaunched(taskThatMustOutliveUsage = {
                try {
                    batching.autoBatchedOnQuiescence {
                        withImportantCleanup {
                            context(SolutionScope(batching, this, active)) { solution() }
                        }
                    }
                } finally {
                    try {
                        if (currentCoroutineContext().isActive && outbox.isNotEmpty()) flush()
                    } finally {
                        submissions.close()
                    }
                }
            }) {
                val result = receivePuzzleMessages(expectations, batching, pending, active) {
                    send(CoroutinePuzzleSolveState.Running(CoroutinePuzzleHistoryBatch.Expectation(it)))
                }
                send(CoroutinePuzzleSolveState.Completed(result))
            }
        }
    }
}

class ExceptionAcrossRpc(message: String, val originCallId: Long? = null) : Exception(message, null, false, false)
class CancellationAcrossRpc : CancellationException(null)

private class LocalPuzzleCall(
    val scope: CoroutineScope,
    val cleanupScope: CoroutineScope,
    val callback: (suspend CoroutineScope.(JsonElement) -> JsonElement)? = null,
) {
    val failures = ConcurrentHashMap<Long, Throwable>()
    @Volatile var cancelledByPeer = false
    @Volatile var cancellationSent = false
    fun cancel() {
        cancelledByPeer = true
        scope.cancel(CancellationAcrossRpc())
    }
}

private class SolutionScope(
    private val batching: AutoBatchedFunctionId<WithCallId<CoroutinePuzzleSubmissionPayload>, JsonElement>,
    private val cleanupScope: CoroutineScope,
    private val active: ConcurrentHashMap<Long, LocalPuzzleCall>,
) : CoroutinePuzzleSolutionScope {
    private val ids = AtomicLong()

    override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement =
        submit(id, t, null)

    override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(
        topic: JsonElement,
        function: suspend CoroutineScope.(JsonElement) -> JsonElement,
    ): JsonElement = submit(id, topic, function)

    private suspend fun submit(
        endpoint: CoroutinePuzzleEndPointId,
        argument: JsonElement,
        callback: (suspend CoroutineScope.(JsonElement) -> JsonElement)?,
    ): JsonElement {
        val id = ids.incrementAndGet()
        var call: LocalPuzzleCall? = null
        try {
            return supervisorScope {
                call = LocalPuzzleCall(this, cleanupScope, callback).also { active[id] = it }
                val payload = if (callback == null) CallSubmitted(endpoint, argument)
                else HigherOrderCallSubmitted(endpoint, buildJsonObject {
                    put("first", argument)
                    put("second", id)
                })
                batching.batched(WithCallId(id, payload))
            }
        } catch (failure: CancellationException) {
            when {
                call?.cancelledByPeer == true -> batching.sendFrom(cleanupScope, id, Outcome.Cancelled)
                !currentCoroutineContext().isActive -> {
                    call?.cancellationSent = true
                    batching.sendFrom(cleanupScope, id, Cancel)
                }
            }
            throw failure
        } finally {
            active.remove(id)
        }
    }
}

private suspend fun AutoBatchedFunctionId<WithCallId<CoroutinePuzzleSubmissionPayload>, JsonElement>.sendFrom(
    scope: CoroutineScope,
    id: Long,
    payload: CoroutinePuzzleSubmissionPayload,
) {
    withContext(NonCancellable) {
        scope.launch { batched(WithCallId(id, payload)) }.join()
    }
}

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
private suspend fun receivePuzzleMessages(
    incoming: ReceiveChannel<CoroutinePuzzleExpectationBatchOrCompletion>,
    batching: AutoBatchedFunctionId<WithCallId<CoroutinePuzzleSubmissionPayload>, JsonElement>,
    pending: ConcurrentHashMap<Long, GuardedContinuation<JsonElement>>,
    active: ConcurrentHashMap<Long, LocalPuzzleCall>,
    onBatch: suspend (CoroutinePuzzleBatch<CoroutinePuzzleExpectationPayload>) -> Unit,
): CoroutinePuzzleSolutionResult {
    for (message in incoming) when (message) {
        is CoroutinePuzzleExpectationBatchOrCompletion.Completion -> return message.result
        is CoroutinePuzzleExpectationBatchOrCompletion.Batch -> {
            onBatch(message.batch)
            val anchor = message.batch.firstNotNullOfOrNull { entry ->
                pending[entry.callId] ?: (entry.payload as? CallbackInvoked)?.let { pending[it.parentCallId] }
            }
            val control = message.batch.filterNot { it.payload is Outcome }
            fun processControl() {
                for ((id, payload) in control) when (payload) {
                    is CallbackInvoked -> {
                        val parent = checkNotNull(active[payload.parentCallId]) { "Callback for a completed call" }
                        val callback = checkNotNull(parent.callback) { "This call did not supply a callback" }
                        val job = parent.scope.launch(start = CoroutineStart.ATOMIC) {
                            val result = runCatching {
                                currentCoroutineContext().ensureActive()
                                coroutineScope { callback(payload.arg) }
                            }
                            result.exceptionOrNull()?.takeUnless { it is CancellationException }?.let { parent.failures[id] = it }
                            batching.sendFrom(parent.cleanupScope, id, result.toOutcome(id))
                            active.remove(id)
                        }
                        active[id] = LocalPuzzleCall(CoroutineScope(parent.scope.coroutineContext + job), parent.cleanupScope)
                    }
                    Cancel -> active[id]?.also {
                        if (!it.cancellationSent) pending.remove(id)
                        it.cancel()
                    }
                    else -> error("Unexpected control message $payload")
                }
            }
            val replies = message.batch.mapNotNull { (id, payload) ->
                if (payload !is Outcome) return@mapNotNull null
                pending.remove(id)?.to(
                    if (active[id]?.cancellationSent == true) Result.success(JsonNull)
                    else payload.toResult(active[id]?.failures.orEmpty()),
                )
            }
            val tracked = anchor ?: replies.firstOrNull()?.first
            if (tracked != null) tracked.runOnScopeThatTracksQuiescence {
                processControl()
                replies.forEach { (continuation, result) -> continuation.resumeWith(result) }
            } else if (control.isNotEmpty()) {
                val scope = control.firstNotNullOfOrNull { active[it.callId]?.cleanupScope }
                checkNotNull(scope).launch { processControl() }.join()
            }
        }
    }
    return CoroutinePuzzleSolutionResult.Success
}

fun Result<JsonElement>.toOutcome(originCallId: Long? = null): Outcome<JsonElement> = fold(
    onSuccess = { Outcome.Returned(it) },
    onFailure = {
        when (it) {
            is CancellationException -> Outcome.Cancelled
            is ExceptionAcrossRpc -> Outcome.Threw(it.message ?: "Call failed", it.originCallId ?: originCallId)
            else -> Outcome.Threw(it.message ?: "Call failed", originCallId)
        }
    },
)

fun <T> Outcome<T>.toResult(localFailures: Map<Long, Throwable> = emptyMap()): Result<T> = when (this) {
    Outcome.Cancelled -> Result.failure(CancellationAcrossRpc())
    is Outcome.Returned -> Result.success(result)
    is Outcome.Threw -> Result.failure(originCallId?.let(localFailures::get) ?: ExceptionAcrossRpc(message, originCallId))
}

@Serializable sealed class CoroutinePuzzleExpectationBatchOrCompletion {
    @Serializable data class Batch(val batch: CoroutinePuzzleBatch<CoroutinePuzzleExpectationPayload>) : CoroutinePuzzleExpectationBatchOrCompletion()
    @Serializable data class Completion(val result: CoroutinePuzzleSolutionResult) : CoroutinePuzzleExpectationBatchOrCompletion()
}

fun coroutinePuzzleCommunicationChannel(
    underlyingComms: suspend CoroutineScope.(
        fromExpectation: SendChannel<CoroutinePuzzleExpectationBatchOrCompletion>,
        fromSubmission: ReceiveChannel<CoroutinePuzzleBatch<CoroutinePuzzleSubmissionPayload>>,
    ) -> Unit,
): Resource<CoroutinePuzzleProtocol> = communicationProtocol(underlyingComms)
