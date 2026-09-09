package kmpworkshop.server

import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleCallPayload.Submission
import kmpworkshop.common.CoroutinePuzzleCallPayload.HigherOrderCallSubmitted
import kmpworkshop.common.CoroutinePuzzleCallPayload.CallbackInvoked
import kmpworkshop.common.CoroutinePuzzleCallPayload.Outcome
import kmpworkshop.common.CoroutinePuzzleSolutionResult.CustomFailure
import kmpworkshop.common.CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure
import kmpworkshop.common.CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure
import kmpworkshop.server.PuzzleEvent.ExpectationBatch
import kmpworkshop.server.PuzzleEvent.SubmissionBatch
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import java.util.concurrent.atomic.AtomicLong

/** Optional exact argument used for matching and diagnostics, serialized before entering the protocol. */
sealed class ExpectedArgument<out T> {
    data object None : ExpectedArgument<Nothing>()
    data class Exact<T>(val value: T) : ExpectedArgument<T>()
}

interface CoroutinePuzzleValueProducerScope {
    suspend fun expectCancellation(): Nothing
}

context(valueProducerScope: CoroutinePuzzleValueProducerScope)
suspend fun expectCancellation(): Nothing = valueProducerScope.expectCancellation()

interface CoroutinePuzzleBuilderScope {
    suspend fun <T, R> expectCallTo(
        endPoint: CoroutinePuzzleEndPoint<T, R>,
        tSerializer: KSerializer<T>,
        rSerializer: KSerializer<R>,
        expectedArgument: ExpectedArgument<T> = ExpectedArgument.None,
        valueProducer: suspend context(CoroutinePuzzleValueProducerScope) CoroutineScope.(T) -> R,
    ): T

    suspend fun <T, T1, R, R1> expectCallTo(
        endPoint: CoroutinePuzzleEndPoint<Pair<T, (T1) -> R1>, R>,
        tSerializer: KSerializer<T>,
        t1Serializer: KSerializer<T1>,
        rSerializer: KSerializer<R>,
        r1Serializer: KSerializer<R1>,
        expectedArgument: ExpectedArgument<T> = ExpectedArgument.None,
        valueProducer: suspend context(CoroutinePuzzleValueProducerScope) CoroutineScope.(T, suspend (T1) -> R1) -> R,
    )

    /**
     * Suspends this expectation branch until both sides are quiescent,
     * then returns unmatched calls and higher-order calls waiting for their next callback invocation,
     * with their arguments. The solution stays suspended.
     */
    suspend fun awaitQuiescenceAndGetUnmatchedSubmissions(): List<UnmatchedSubmission<*>>
}

context(serverMetadata: ServerMetadata)
fun coroutinePuzzleWithMetadata(
    builder: suspend context(CoroutinePuzzleBuilderScope) CoroutineScope.() -> Unit,
): Resource<CoroutinePuzzleProtocol> = coroutinePuzzleCommunicationChannel { outgoing, incoming ->
    val events = Channel<PuzzleEvent>(capacity = Channel.UNLIMITED)

    val expectationBatching =
        AutoBatchedFunctionId(batchResumer = { batch ->
            events.send(ExpectationBatch(batch))
        })

    try {
        withImportantCleanup {
            withLaunched(
                taskThatMustOutliveUsage = {
                    withLaunched(
                        taskThatMustOutliveUsage = {
                            try {
                                for (batch in incoming) events.send(SubmissionBatch(batch))
                            } finally {
                                importantCleanup {
                                    events.send(SubmissionBatch(null)) // null means that submissions are over
                                }
                            }
                        },
                    ) {
                        expectationBatching.runExpectations(builder)
                    }
                },
            ) {
                PuzzleActor(
                    events,
                    emitBatch = {
                        outgoing.send(CoroutinePuzzleExpectationBatchOrCompletion.Batch(it))
                    },
                    serverMetadata,
                ).run()
            }.also { outgoing.send(CoroutinePuzzleExpectationBatchOrCompletion.Completion(it)) }
        }
    } catch (c: CoroutinePuzzleFailedControlFlowException) {
        outgoing.send(CoroutinePuzzleExpectationBatchOrCompletion.Completion(c.result))
    } finally {
        outgoing.close()
    }
}

fun coroutinePuzzle(
    builder: suspend context(CoroutinePuzzleBuilderScope) CoroutineScope.() -> Unit,
): Resource<CoroutinePuzzleProtocol> = context(defaultServerMetadata) { coroutinePuzzleWithMetadata(builder) }

fun coroutinePuzzle(
    serverMetadata: ServerMetadata,
    builder: suspend context(CoroutinePuzzleBuilderScope) CoroutineScope.() -> Unit,
): Resource<CoroutinePuzzleProtocol> = context(serverMetadata) { coroutinePuzzleWithMetadata(builder) }

private suspend fun AutoBatchedFunctionId<EvaluatorRequest, EvaluatorResult?>.runExpectations(
    builder: suspend context(CoroutinePuzzleBuilderScope) CoroutineScope.() -> Unit,
) = autoBatchedOnQuiescence {
    val result = runCatching {
        coroutineScope {
            context(PuzzleBuilder(this@runExpectations, this@autoBatchedOnQuiescence, coroutineContext.job)) {
                builder()
            }
        }
    }
    try {
        batched(EvaluatorRequest.AwaitQuiescence(finishExpectations = true))
    } catch (failure: Exception) {
        result.exceptionOrNull()?.addSuppressed(failure) ?: throw failure
    }
    result.getOrThrow()
}

private class PuzzleBuilder(
    private val batching: AutoBatchedFunctionId<EvaluatorRequest, EvaluatorResult?>,
    private val cleanupScope: CoroutineScope,
    private val evaluatorJob: Job,
) : CoroutinePuzzleBuilderScope {
    private val callbackIds = AtomicLong()

    override suspend fun <T, R> expectCallTo(
        endPoint: CoroutinePuzzleEndPoint<T, R>,
        tSerializer: KSerializer<T>,
        rSerializer: KSerializer<R>,
        expectedArgument: ExpectedArgument<T>,
        valueProducer: suspend context(CoroutinePuzzleValueProducerScope) CoroutineScope.(T) -> R,
    ): T = Json.decodeFromJsonElement(tSerializer, expectRawCall(endPoint, expectedArgument.encodeWith(tSerializer)) { call ->
        val argument = Json.decodeFromJsonElement(tSerializer, call.payload.arg)
        Json.encodeToJsonElement(rSerializer, valueProducer(argument))
    })

    override suspend fun <T, T1, R, R1> expectCallTo(
        endPoint: CoroutinePuzzleEndPoint<Pair<T, (T1) -> R1>, R>,
        tSerializer: KSerializer<T>,
        t1Serializer: KSerializer<T1>,
        rSerializer: KSerializer<R>,
        r1Serializer: KSerializer<R1>,
        expectedArgument: ExpectedArgument<T>,
        valueProducer: suspend context(CoroutinePuzzleValueProducerScope) CoroutineScope.(T, suspend (T1) -> R1) -> R,
    ) {
        expectRawCall(endPoint, expectedArgument.encodeWith(tSerializer), higherOrder = true) { call ->
            val argument = Json.decodeFromJsonElement(tSerializer, call.payload.arg.jsonObject.getValue("first"))
            val owner = this
            Json.encodeToJsonElement(rSerializer, valueProducer(argument) { value ->
                runInOwningScope(owner) {
                    val invocation = CallbackRequest(
                        callbackIds.decrementAndGet(), call.callId, Json.encodeToJsonElement(t1Serializer, value),
                    )
                    try {
                        val result = batching.batched(EvaluatorRequest.InvokeCallback(invocation))
                            as EvaluatorResult.CallbackCompleted
                        Json.decodeFromJsonElement(r1Serializer, result.outcome.toResult().getOrThrow())
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive && evaluatorJob.isActive) {
                            withContext(NonCancellable) {
                                cleanupScope.launch {
                                    batching.batched(EvaluatorRequest.CancelCallback(invocation))
                                }.join()
                            }
                        }
                        throw cancelled
                    }
                }
            })
        }
    }

    private suspend fun expectRawCall(
        endPoint: CoroutinePuzzleEndPoint<*, *>,
        expectedArgument: JsonElement?,
        higherOrder: Boolean = false,
        valueProducer: suspend context(CoroutinePuzzleValueProducerScope) CoroutineScope.(WithCallId<Submission>) -> JsonElement,
    ): JsonElement {
        val expectation = ExpectedCall(
            CoroutinePuzzleExpectedFollowup(endPoint.id, expectedArgument), higherOrder,
        )
        evaluatorJob.ensureActive()
        // Remote cancellation stops the producer, not the expectation branch observing its result.
        // Local cancellation explicitly cancels and joins this cleanupScope child below.
        // Register the job before matching so even prompt cancellation has an owner to cancel.
        var produced: Result<ProducedCall>? = null
        val producer = cleanupScope.launch {
            produced = runCatching {
                val (call) = batching.batched(EvaluatorRequest.Expectation(
                    expectation, coroutineContext.job,
                )) as EvaluatorResult.MatchedSubmission
                val valueProducerScope = object : CoroutinePuzzleValueProducerScope {
                    override suspend fun expectCancellation(): Nothing {
                        batching.batched(EvaluatorRequest.AwaitCancellation(call.callId))
                        awaitCancellation()
                    }
                }
                ProducedCall(call.payload.arg, context(valueProducerScope) { coroutineScope { valueProducer(call) } })
            }
        }
        return try {
            producer.join()
            val result = checkNotNull(produced)
            try {
                evaluatorJob.ensureActive()
                val completion = batching.batched(EvaluatorRequest.CompleteCall(
                    expectation, result.map { it.result }.toOutcome(),
                )) as EvaluatorResult.SubmissionCompleted
                completion.outcome.toResult().getOrThrow()
            } catch (t: Throwable) {
                result.exceptionOrNull()?.addSuppressed(t) ?: throw t
            }
            result.getOrThrow().argument
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                producer.cancel()
                // Attempt teardown already cancels both sides. Do not delay the original evaluator failure
                // behind per-call cancellation handshakes, but join both sides for a cancelled branch.
                if (evaluatorJob.isActive) {
                    withContext(NonCancellable) {
                        cleanupScope.launch {
                            try {
                                batching.batched(EvaluatorRequest.CancelExpectation(expectation))
                            } finally {
                                producer.join()
                            }
                        }.join()
                    }
                }
            }
            throw cancellation
        }
    }

    override suspend fun awaitQuiescenceAndGetUnmatchedSubmissions(): List<UnmatchedSubmission<*>> =
        (batching.batched(EvaluatorRequest.AwaitQuiescence(finishExpectations = false))
            as EvaluatorResult.QuiescenceReached).unmatchedSubmissions
}

private typealias RawExpectationBatch =
    List<SuspendedBatchCall<EvaluatorRequest, EvaluatorResult?>>
private typealias RawSubmissionBatch = List<WithCallId<CoroutinePuzzleSubmissionPayload>>

private typealias EvaluatorContinuation = GuardedContinuation<EvaluatorResult?>

private data class ProducedCall(val argument: JsonElement, val result: JsonElement)

/**
 * Only PuzzleActor replaces these snapshots; messages never share mutable call records.
 * Producer and submission progress independently because cancellation can cross a returned answer.
 */
private data class SubmittedCall(
    val submission: WithCallId<Submission>,
    val producer: ProducerState = ProducerState.Unmatched,
    val peer: SubmissionState = SubmissionState.Active,
) {
    val id: Long get() = submission.callId
    val isUnmatched: Boolean get() = producer == ProducerState.Unmatched
    val expectation: RegisteredExpectation? get() = producer.expectation
}

/** The evaluator is either producing, returning its result, or joining a call it cancelled. */
private sealed interface ProducerState {
    val expectation: RegisteredExpectation?
    data object Unmatched : ProducerState { override val expectation: Nothing? = null }
    data class Running(
        override val expectation: RegisteredExpectation,
        val expectsCancellation: Boolean = false,
    ) : ProducerState
    data class Returning(
        override val expectation: RegisteredExpectation,
        val outcome: Outcome<JsonElement>,
        val continuation: EvaluatorContinuation,
    ) : ProducerState
    data class Cancelling(
        override val expectation: RegisteredExpectation,
        val continuation: EvaluatorContinuation,
        /** Preserve an answer that raced with cancellation, rather than inventing a different result. */
        val previousOutcome: Outcome<JsonElement>?,
    ) : ProducerState
}

/** Cancellation requested by the solution must reach the producer before its completion can be settled. */
private sealed interface SubmissionState {
    data object Active : SubmissionState
    data object CancellationRequested : SubmissionState
    data object CancellationForwarded : SubmissionState
    data class Completed(val outcome: Outcome<Unit>) : SubmissionState
}

private data class CallbackRequest(val id: Long, val parentCallId: Long, val argument: JsonElement)

private data class CallbackInvocation(
    val request: CallbackRequest,
    val continuation: EvaluatorContinuation,
    val state: CallbackState,
)

private sealed interface CallbackState {
    data object Queued : CallbackState
    data object Running : CallbackState
    data object CancellationRequested : CallbackState
    data class Completed(val outcome: Outcome<JsonElement>) : CallbackState
}

private data class EvaluatorResumption(val continuation: EvaluatorContinuation, val result: EvaluatorResult)

private fun Outcome<*>.toUnitOutcome(): Outcome<Unit> = when (this) {
    is Outcome.Returned -> Outcome.Returned(Unit)
    is Outcome.Threw -> this
    Outcome.Cancelled -> Outcome.Cancelled
}

/**
 * Bounces quiescence between the evaluator and solution sides.
 *
 * Only advance one side at a time. Completion notifications settle the evaluator before any
 * further replies release the solution; cancellation requests never stand in for completion.
 */
private class PuzzleActor(
    private val events: ReceiveChannel<PuzzleEvent>,
    private val emitBatch: suspend (CoroutinePuzzleBatch<CoroutinePuzzleExpectationPayload>) -> Unit,
    private val serverMetadata: ServerMetadata,
) {
    private val calls = mutableMapOf<Long, SubmittedCall>()
    private val callbacks = mutableMapOf<Long, CallbackInvocation>()
    private val expectations = mutableListOf<RegisteredExpectation>()
    private val expectationCancellations = mutableListOf<ExpectationCancellation>()
    private val quiescenceWaiters =
        mutableListOf<GuardedContinuation<EvaluatorResult.QuiescenceReached>>()
    private var finishExpectationsWhenQuiescent = false
    private var submissionsClosed = false

    suspend fun run(): CoroutinePuzzleSolutionResult {
        val first = events.receive()
        val second = events.receive()
        check((first is ExpectationBatch) != (second is ExpectationBatch)) {
            "The first two puzzle events must contain one expectation batch and one submission batch."
        }
        accept(first)
        accept(second)

        while (true) {
            // Settle cancellation and completion before releasing more solution work.
            val (immediate, remote) = takeExpectationCancellations()
            if (immediate.isNotEmpty()) advanceEvaluator(immediate)
            if (remote.isNotEmpty()) advanceSolution(remote.map { WithCallId(it, CoroutinePuzzleCallPayload.Cancel) })
            if (immediate.isNotEmpty() || remote.isNotEmpty()) continue

            val completions = takeCompletions()
            if (completions.isNotEmpty()) {
                advanceEvaluator(completions)
                continue
            }

            if (submissionsClosed) {
                val followups = expectedFollowups()
                if (followups.isEmpty() && quiescenceWaiters.isNotEmpty()) {
                    advanceEvaluator { resumeQuiescenceWaiters() }
                    continue
                }
                return if (finishExpectationsWhenQuiescent) finishedResult()
                else MoreExpectationsThanSubmissionsFailure(followups)
            }

            val replies = takeReplies()
            if (replies.isNotEmpty()) {
                advanceSolution(replies)
                continue
            }

            val matches = takeMatches()
            val cancellations = takeSubmissionCancellations()
            if (matches.isNotEmpty() || cancellations.isNotEmpty()) {
                advanceEvaluator(matches, cancellations)
                continue
            }

            val callbackRequests = takeCallbackRequests()
            if (callbackRequests.isNotEmpty()) {
                advanceSolution(callbackRequests)
                continue
            }

            if (finishExpectationsWhenQuiescent) return finishedResult()

            if (quiescenceWaiters.isNotEmpty()) {
                advanceEvaluator { resumeQuiescenceWaiters() }
                continue
            }

            return stalledResult()
        }
    }

    private suspend fun advanceEvaluator(
        completions: List<EvaluatorResumption>,
        cancellations: List<RegisteredExpectation> = emptyList(),
    ) = advanceEvaluator {
        val anchor = completions.firstOrNull()?.continuation ?: cancellations.first().continuation
        // Even cancellation of a producer in NonCancellable must schedule a tracked turn:
        // its next idle batch lets the actor inspect any remaining cleanup work.
        anchor.runOnScopeThatTracksQuiescence {
            cancellations.forEach { it.producer.cancel(CancellationAcrossRpc()) }
            completions.forEach { (continuation, result) -> continuation.resume(result) }
        }
    }

    private suspend fun advanceEvaluator(resume: suspend () -> Unit) {
        resume()
        while (true) {
            val event = events.receive()
            // Final one-way notifications may be followed by closure while the evaluator runs.
            check(event is ExpectationBatch || event is SubmissionBatch && event.submissions == null) {
                "Expected an expectation batch, but received a submission batch."
            }
            accept(event)
            if (event is ExpectationBatch) return
        }
    }

    private suspend fun advanceSolution(batch: CoroutinePuzzleBatch<CoroutinePuzzleExpectationPayload>) {
        emitBatch(batch)
        if (submissionsClosed) return
        val event = events.receive()
        check(event is SubmissionBatch) { "Expected a submission batch, but received an expectation batch." }
        accept(event)
    }

    private fun accept(event: PuzzleEvent) {
        when (event) {
            is ExpectationBatch -> event.expectations.forEach(::acceptExpectation)
            is SubmissionBatch -> {
                if (event.submissions == null) submissionsClosed = true
                else event.submissions.forEach(::acceptSubmission)
            }
        }
    }

    private fun acceptExpectation(entry: SuspendedBatchCall<EvaluatorRequest, EvaluatorResult?>) {
        val continuation = entry.continuation
        when (val query = entry.query) {
            is EvaluatorRequest.Expectation ->
                expectations += RegisteredExpectation(query.call, query.producer, continuation)
            is EvaluatorRequest.CancelExpectation ->
                expectationCancellations += ExpectationCancellation(query.expectation, continuation)
            is EvaluatorRequest.AwaitCancellation -> {
                val call = calls.getValue(query.callId)
                val producer = call.producer as ProducerState.Running
                calls[call.id] = call.copy(producer = producer.copy(expectsCancellation = true))
            }
            is EvaluatorRequest.CompleteCall -> {
                val call = checkNotNull(callFor(query.expectation)) { "Completed an unmatched expectation" }
                calls[call.id] = call.copy(producer = ProducerState.Returning(
                    checkNotNull(call.expectation), query.outcome, continuation,
                ))
            }
            is EvaluatorRequest.InvokeCallback -> {
                val request = query.invocation
                // The enclosing call may have been cancelled after this batch was published.
                val parentActive = calls[request.parentCallId]?.expectation?.producer?.isActive == true
                callbacks[request.id] = CallbackInvocation(request, continuation,
                    if (parentActive) CallbackState.Queued else CallbackState.Completed(Outcome.Cancelled))
            }
            is EvaluatorRequest.CancelCallback -> {
                val request = query.invocation
                val previous = callbacks[request.id]?.state
                callbacks[request.id] = CallbackInvocation(request, continuation, when (previous) {
                    // Missing means it never reached the actor, or its completion already resumed the
                    // cancelled caller. In either case there is no remote work left to join.
                    null, CallbackState.Queued -> CallbackState.Completed(Outcome.Cancelled)
                    is CallbackState.Completed -> previous
                    else -> CallbackState.CancellationRequested
                })
            }
            is EvaluatorRequest.AwaitQuiescence ->
                if (query.finishExpectations) finishExpectationsWhenQuiescent = true
                else quiescenceWaiters += continuation
        }
    }

    private fun acceptSubmission(entry: WithCallId<CoroutinePuzzleSubmissionPayload>) {
        val (callId, payload) = entry
        when (payload) {
            is Submission -> check(calls.put(callId, SubmittedCall(WithCallId(callId, payload))) == null) {
                "Duplicate submission for call $callId"
            }
            CoroutinePuzzleCallPayload.Cancel -> {
                val call = calls[callId] ?: failInternal(CustomFailure(
                    "Unexpected cancellation for call $callId: its expectation was not running.",
                ))
                calls[callId] = call.copy(peer = SubmissionState.CancellationRequested)
            }
            is Outcome -> {
                val callback = callbacks[callId]
                if (callback != null) {
                    check(callback.state !is CallbackState.Completed) { "Duplicate callback outcome $callId" }
                    callbacks[callId] = callback.copy(state = CallbackState.Completed(payload))
                } else {
                    val call = calls[callId] ?: unexpectedCompletion(callId)
                    if (call.isUnmatched) unexpectedCompletion(callId)
                    check(call.peer !is SubmissionState.Completed) { "Duplicate completion for call $callId" }
                    calls[callId] = call.copy(peer = SubmissionState.Completed(payload.toUnitOutcome()))
                }
            }
            is CallbackInvoked -> error("The solution cannot invoke its own callback")
        }
    }

    private fun callFor(expectation: ExpectedCall): SubmittedCall? =
        calls.values.firstOrNull { it.expectation?.call === expectation }

    private fun takeExpectationCancellations(): Pair<List<EvaluatorResumption>, List<Long>> {
        val immediate = mutableListOf<EvaluatorResumption>()
        val remote = mutableListOf<Long>()
        for ((expectation, continuation) in expectationCancellations) {
            expectations.removeAll { it.call === expectation }
            val call = callFor(expectation)
            if (call == null) {
                immediate +=
                    EvaluatorResumption(continuation, EvaluatorResult.SubmissionCompleted(Outcome.Returned(Unit)))
                continue
            }
            calls[call.id] = call.copy(producer = ProducerState.Cancelling(
                checkNotNull(call.expectation),
                continuation,
                (call.producer as? ProducerState.Returning)?.outcome,
            ))
            if (call.peer != SubmissionState.CancellationForwarded && call.peer !is SubmissionState.Completed) {
                remote += call.id
            }
        }
        expectationCancellations.clear()
        return immediate to remote
    }

    private fun takeCompletions(): List<EvaluatorResumption> = buildList {
        val callbackIterator = callbacks.values.iterator()
        while (callbackIterator.hasNext()) {
            val callback = callbackIterator.next()
            val completed = callback.state as? CallbackState.Completed ?: continue
            add(EvaluatorResumption(callback.continuation, EvaluatorResult.CallbackCompleted(completed.outcome)))
            callbackIterator.remove()
        }
        val iterator = calls.values.iterator()
        while (iterator.hasNext()) {
            val call = iterator.next()
            val completed = call.peer as? SubmissionState.Completed ?: continue
            val continuation = when (val producer = call.producer) {
                ProducerState.Unmatched -> null // Cancellation of a call that never matched.
                is ProducerState.Running -> unexpectedCompletion(call.id)
                is ProducerState.Returning -> producer.continuation
                is ProducerState.Cancelling -> producer.continuation
            }
            if (continuation != null) add(EvaluatorResumption(continuation,
                EvaluatorResult.SubmissionCompleted(completed.outcome)))
            iterator.remove()
        }
    }

    private fun unexpectedCompletion(callId: Long): Nothing = failInternal(CustomFailure(
        "Unexpected completion for call $callId: its expectation was not awaiting completion.",
    ))

    /** All replies share one path; a crossing cancellation replays the producer's existing outcome. */
    private fun takeReplies(): List<WithCallId<Outcome<JsonElement>>> = buildList {
        for ((id, call) in calls) {
            val cancelling = call.peer == SubmissionState.CancellationRequested || call.peer == SubmissionState.CancellationForwarded
            val outcome = when (val producer = call.producer) {
                is ProducerState.Returning -> producer.outcome
                ProducerState.Unmatched -> if (cancelling) Outcome.Cancelled else continue
                is ProducerState.Cancelling -> {
                    if (!cancelling || !producer.expectation.producer.isCompleted) continue
                    producer.previousOutcome ?: Outcome.Cancelled
                }
                is ProducerState.Running -> continue
            }
            // Returning the value finishes a call. No result-consumption acknowledgement is needed.
            calls[id] = call.copy(peer = SubmissionState.Completed(Outcome.Returned(Unit)))
            add(WithCallId(id, outcome))
        }
    }

    private fun takeCallbackRequests(): List<WithCallId<CoroutinePuzzleExpectationPayload>> = buildList {
        for ((id, callback) in callbacks) {
            val payload = when (callback.state) {
                CallbackState.Queued -> CallbackInvoked(callback.request.parentCallId, callback.request.argument)
                CallbackState.CancellationRequested -> CoroutinePuzzleCallPayload.Cancel
                else -> continue
            }
            callbacks[id] = callback.copy(state = CallbackState.Running)
            add(WithCallId(id, payload))
        }
    }

    private fun takeMatches(): List<EvaluatorResumption> = buildList {
        for (call in calls.values.filter { it.isUnmatched }) {
            val submission = call.submission.payload
            val argument = if (submission is HigherOrderCallSubmitted) submission.arg.jsonObject.getValue("first") else submission.arg
            fun accepts(expectation: RegisteredExpectation): Boolean =
                expectation.call.higherOrder == (submission is HigherOrderCallSubmitted) && expectation.call.expectedFollowup.endPoint == submission.endPoint
            // Addressed calls must not consume another caller's request. Prefer exact over wildcard.
            val exactIndex = expectations.indexOfLast {
                accepts(it) && it.call.expectedFollowup.expectedArgument == argument
            }
            val index = exactIndex.takeIf { it >= 0 } ?: expectations.indexOfLast {
                accepts(it) && it.call.expectedFollowup.expectedArgument == null
            }
            if (index >= 0) {
                val expectation = expectations.removeAt(index)
                calls[call.id] = call.copy(producer = ProducerState.Running(expectation))
                add(EvaluatorResumption(expectation.continuation,
                    EvaluatorResult.MatchedSubmission(call.submission)))
            }
        }
    }

    private fun takeSubmissionCancellations(): List<RegisteredExpectation> = buildList {
        for ((id, call) in calls) {
            if (call.peer != SubmissionState.CancellationRequested) continue
            val expectation = call.expectation ?: failInternal(CustomFailure(
                "Unexpected cancellation for call $id: its expectation was not running.",
            ))
            calls[id] = call.copy(
                peer = SubmissionState.CancellationForwarded,
                producer = (call.producer as? ProducerState.Running)?.copy(expectsCancellation = false) ?: call.producer,
            )
            add(expectation)
        }
    }

    private fun unmatchedSubmissions(): List<WithCallId<Submission>> =
        calls.values.filter { it.isUnmatched }.map { it.submission }

    private fun expectedFollowups(): List<CoroutinePuzzleExpectedFollowup> = buildList {
        addAll(expectations.map { it.call.expectedFollowup })
        for (call in calls.values) {
            val producer = call.producer
            if (producer is ProducerState.Running && producer.expectsCancellation) {
                add(CoroutinePuzzleExpectedFollowup(call.submission.payload.endPoint, expectedCancellationOfCallId = call.id))
            }
        }
        for (call in calls.values) when (val producer = call.producer) {
            is ProducerState.Returning -> add(CoroutinePuzzleExpectedFollowup(call.submission.payload.endPoint))
            is ProducerState.Cancelling -> add(producer.expectation.call.expectedFollowup.copy(
                expectedCancellationCompletionOfCallId = call.id,
            ))
            else -> Unit
        }
        for ((request) in callbacks.values) {
            val parent = calls.getValue(request.parentCallId)
            // A cancelling outer call already describes the cleanup we're waiting for.
            if (parent.producer !is ProducerState.Cancelling) add(CoroutinePuzzleExpectedFollowup(
                parent.submission.payload.endPoint, expectedCallbackCompletionOfCallId = request.id,
            ))
        }
    }

    private fun stalledResult(): CoroutinePuzzleSolutionResult {
        val followups = expectedFollowups()
        // A producer waiting only on local primitives has no protocol-visible followup.
        // An unmatched cancelled call, however, still owes its terminal notification.
        if (followups.isEmpty() && calls.values.none { it.isUnmatched }) {
            return CoroutinePuzzleSolutionResult.FullyQuiescent
        }
        val unexpectedIds = unmatchedSubmissions().map { it.payload.endPoint }
        if (unexpectedIds.isEmpty() && followups.all {
                it.expectedCancellationOfCallId == null && it.expectedCallbackCompletionOfCallId == null &&
                    it.expectedCancellationCompletionOfCallId == null
            }) return MoreExpectationsThanSubmissionsFailure(followups)
        return CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
            unexpectedSubmissions = unexpectedIds.filterNot(serverMetadata::isFlowEndpoint).ifEmpty { unexpectedIds },
            expectations = followups,
        )
    }

    private fun finishedResult(): CoroutinePuzzleSolutionResult {
        val followups = expectedFollowups()
        val submissions = unmatchedSubmissions()
        return when {
            followups.isNotEmpty() -> MoreExpectationsThanSubmissionsFailure(followups)
            submissions.isNotEmpty() -> MoreSubmissionsThanExpectationsFailure(submissions.map { it.payload.endPoint })
            else -> CoroutinePuzzleSolutionResult.Success
        }
    }

    private suspend fun resumeQuiescenceWaiters() {
        val invokingCallbacks = callbacks.values.mapTo(mutableSetOf()) { it.request.parentCallId }
        val idleHigherOrderCalls = calls.values.filter { call ->
            call.submission.payload is HigherOrderCallSubmitted && call.producer is ProducerState.Running &&
                call.peer == SubmissionState.Active && call.id !in invokingCallbacks
        }.map { it.submission }
        val result = EvaluatorResult.QuiescenceReached(
            (unmatchedSubmissions() + idleHigherOrderCalls).map(serverMetadata::unmatchedSubmission),
        )
        quiescenceWaiters.toList().also { quiescenceWaiters.clear() }
            .resumeAllQuiescentTrackedScope({ it }) { it.resume(result) }
    }
}

private data class RegisteredExpectation(
    val call: ExpectedCall,
    val producer: Job,
    val continuation: EvaluatorContinuation,
)

private data class ExpectationCancellation(val call: ExpectedCall, val continuation: EvaluatorContinuation)

/** Identity distinguishes simultaneous identical expectations; the specification itself never changes. */
private class ExpectedCall(val expectedFollowup: CoroutinePuzzleExpectedFollowup, val higherOrder: Boolean)

private sealed class EvaluatorRequest {
    data class Expectation(val call: ExpectedCall, val producer: Job) : EvaluatorRequest()
    data class CancelExpectation(val expectation: ExpectedCall) : EvaluatorRequest()
    data class AwaitCancellation(val callId: Long) : EvaluatorRequest()
    data class InvokeCallback(val invocation: CallbackRequest) : EvaluatorRequest()
    data class CancelCallback(val invocation: CallbackRequest) : EvaluatorRequest()
    /** Publish the producer outcome and await the submission's outcome in one operation. */
    data class CompleteCall(
        val expectation: ExpectedCall,
        val outcome: Outcome<JsonElement>,
    ) : EvaluatorRequest()
    data class AwaitQuiescence(val finishExpectations: Boolean) : EvaluatorRequest()
}

private sealed class EvaluatorResult {
    data class CallbackCompleted(val outcome: Outcome<JsonElement>) : EvaluatorResult()
    data class SubmissionCompleted(val outcome: Outcome<Unit>) : EvaluatorResult()
    data class MatchedSubmission(val call: WithCallId<Submission>) : EvaluatorResult()
    data class QuiescenceReached(
        val unmatchedSubmissions: List<UnmatchedSubmission<*>>,
    ) : EvaluatorResult()
}

private sealed interface PuzzleEvent {
    data class SubmissionBatch(
        /** Null means submissions are done. */
        val submissions: RawSubmissionBatch?,
    ) : PuzzleEvent

    data class ExpectationBatch(val expectations: RawExpectationBatch) : PuzzleEvent
}

private fun failInternal(reason: CoroutinePuzzleSolutionResult): Nothing =
    throw CoroutinePuzzleFailedControlFlowException(reason)

context(_: CoroutinePuzzleSolutionScope)
internal fun fail(reason: CoroutinePuzzleSolutionResult): Nothing =
    failInternal(reason)

class CoroutinePuzzleFailedControlFlowException(
    val result: CoroutinePuzzleSolutionResult,
) : Exception(null, null, false, false)

context(_: CoroutinePuzzleBuilderScope)
fun fail(reason: CoroutinePuzzleSolutionResult): Nothing = failInternal(reason)

context(_: CoroutinePuzzleBuilderScope)
fun fail(message: String): Nothing = fail(CustomFailure(message))

/**
 * Fails a puzzle-specific check while preserving which already-submitted calls were conclusively incorrect.
 *
 * Use this after [awaitQuiescenceAndGetUnmatchedSubmissions] only when the check can distinguish an unexpected
 * submission from another call that merely remains suspended.
 */
context(_: CoroutinePuzzleBuilderScope)
fun fail(
    message: String,
    incorrectSubmissions: List<CoroutinePuzzleEndPoint<*, *>>,
): Nothing = fail(CustomFailure(message, incorrectSubmissions.map { it.id }))

context(builder: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint</* @Exact */T, /* @Exact */R>.expectCall(
    noinline valueProducer: suspend context(CoroutinePuzzleValueProducerScope) CoroutineScope.(T) -> R,
): T = builder.expectCallTo(this, serializer(), serializer(), valueProducer = valueProducer)

context(builder: CoroutinePuzzleBuilderScope)
suspend inline fun <reified A, reified T, reified U, reified R> CoroutinePuzzleEndPoint<Pair<A, (T) -> U>, R>.expectCall(
    noinline valueProducer: suspend context(CoroutinePuzzleValueProducerScope) CoroutineScope.(A, suspend (T) -> U) -> R,
) = builder.expectCallTo(this, serializer<A>(), serializer<T>(), serializer<R>(), serializer<U>(), valueProducer = valueProducer)

context(_: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint<T, R>.expectThrowingCall(message: String) {
    try {
        expectCall { throw ExceptionAcrossRpc(message) }
    } catch (failure: ExceptionAcrossRpc) {
        if (failure.message != message) throw failure
    }
}

@JvmName("expectThrowingHigherOrderCall")
context(_: CoroutinePuzzleBuilderScope)
suspend inline fun <reified A, reified T, reified U, reified R> CoroutinePuzzleEndPoint<Pair<A, (T) -> U>, R>.expectThrowingCall(message: String) {
    try {
        expectCall { _, _ -> throw ExceptionAcrossRpc(message) }
    } catch (failure: ExceptionAcrossRpc) {
        if (failure.message != message) throw failure
    }
}

context(_: CoroutinePuzzleBuilderScope)
suspend inline fun <reified A, reified T, reified U, reified R> CoroutinePuzzleEndPoint<Pair<A, (T) -> U>, R>.expectCanceledCall(
    noinline valueProducer: suspend context(CoroutinePuzzleValueProducerScope) CoroutineScope.(A, suspend (T) -> U) -> R,
): CancellationException {
    try {
        expectCall(valueProducer)
    } catch (failure: CancellationException) {
        return failure
    }
    fail("CancellationException was expected")
}

context(builder: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint</* @Exact */T, /* @Exact */R>.expectCanceledCall(
    noinline valueProducer: suspend context(CoroutinePuzzleValueProducerScope) CoroutineScope.(T) -> R,
): CancellationException {
    try {
        builder.expectCallTo(this, serializer(), serializer(), valueProducer = valueProducer)
    } catch (t: CancellationException) {
        return t
    }
    fail("CancellationException was expected")
}

context(builder: CoroutinePuzzleBuilderScope)
suspend fun awaitQuiescenceAndGetUnmatchedSubmissions(): List<UnmatchedSubmission<*>> =
    builder.awaitQuiescenceAndGetUnmatchedSubmissions()

/**
 * When the submissions aren't matched, this function is invoked to provide a custom error message.
 * If the function is null, or the message returned a null, then the default error message is used.
 */
typealias UnmatchedSubmissionMessageFunction = ((List<UnmatchedSubmission<*>>) -> String?)

context(_: CoroutinePuzzleBuilderScope)
fun verifyUnmatchedSubmissions(
    submissions: List<UnmatchedSubmission<*>>,
    expected: List<CoroutinePuzzleEndPoint<*, *>>,
    message: UnmatchedSubmissionMessageFunction? = null,
) {
    if (submissions.groupingBy { it.endPoint.id }.eachCount() != expected.groupingBy { it.id }.eachCount()) {
        val incorrectSubmissions = submissions.incorrectComparedTo(expected)
        message?.invoke(submissions)?.let { fail(it, incorrectSubmissions.map { submission -> submission.endPoint }) }
        fail(CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure(
            submissions = submissions.map { it.endPoint.id },
            expectations = expected.map { CoroutinePuzzleExpectedFollowup(it.id) },
            incorrectSubmissions = incorrectSubmissions.map { it.endPoint.id },
        ))
    }
}

/** The remaining submissions after pairing each expected endpoint with one actual submission. */
private fun List<UnmatchedSubmission<*>>.incorrectComparedTo(
    expected: List<CoroutinePuzzleEndPoint<*, *>>,
): List<UnmatchedSubmission<*>> {
    val remainingExpectedCounts = expected.groupingBy { it.id }.eachCount().toMutableMap()
    return filter { submission ->
        val remaining = remainingExpectedCounts[submission.endPoint.id] ?: 0
        if (remaining == 0) {
            true
        } else {
            remainingExpectedCounts[submission.endPoint.id] = remaining - 1
            false
        }
    }
}

context(_: CoroutinePuzzleBuilderScope)
suspend fun awaitQuiescenceAndVerifyUnmatchedSubmissions(
    vararg expected: CoroutinePuzzleEndPoint<*, *>,
    message: UnmatchedSubmissionMessageFunction? = null,
) {
    awaitQuiescenceAndVerifyUnmatchedSubmissions(expected.asList(), message)
}

context(_: CoroutinePuzzleBuilderScope)
suspend fun awaitQuiescenceAndVerifyUnmatchedSubmissions(
    expected: List<CoroutinePuzzleEndPoint<*, *>>,
    message: UnmatchedSubmissionMessageFunction? = null,
) {
    verifyUnmatchedSubmissions(awaitQuiescenceAndGetUnmatchedSubmissions(), expected, message)
}

context(builder: CoroutinePuzzleBuilderScope)
inline fun verify(condition: Boolean, message: () -> String) {
    if (!condition) fail(CustomFailure(message()))
}
context(builder: CoroutinePuzzleBuilderScope)
inline fun <T : Any> T?.verifyNotNull(message: () -> String): T =
    this ?: fail(CustomFailure(message()))

context(builder: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint</* @Exact */ R, /* @Exact */ T>.expectCall(
    value: T,
): R = expectCall { value }


private fun <T> ExpectedArgument<T>.encodeWith(serializer: KSerializer<T>): JsonElement? = when (this) {
    ExpectedArgument.None -> null
    is ExpectedArgument.Exact -> Json.encodeToJsonElement(serializer, value)
}
