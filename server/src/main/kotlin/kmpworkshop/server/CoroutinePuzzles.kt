package kmpworkshop.server

import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleExpectationPayload
import kmpworkshop.common.CoroutinePuzzleSolutionResult.CustomFailure
import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure
import kmpworkshop.common.CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.map
import kotlin.collections.plus
import kotlin.time.ExperimentalTime

interface CoroutinePuzzleBuilderScope {
    suspend fun <T, R> expectCallTo(
        endPoint: CoroutinePuzzleEndPoint<T, R>,
        tSerializer: KSerializer<T>,
        rSerializer: KSerializer<R>,
        valueProducer: suspend (T) -> R,
    ): T

    /**
     * Suspends this expectation branch until every other branch on both sides is quiescent, then returns the
     * submissions that still have no matching expectation. Only the expectation side is resumed; the submission
     * side remains suspended until an expectation produces a result.
     */
    suspend fun awaitQuiescenceAndGetUnmatchedSubmissions(): List<CoroutinePuzzleEndPoint<*, *>>
}

context(serverMetadata: ServerMetadata)
fun coroutinePuzzleWithMetadata(
    builder: suspend context(CoroutinePuzzleBuilderScope) CoroutineScope.() -> Unit,
): Resource<CoroutinePuzzleProtocol> = coroutinePuzzleCommunicationChannel { outgoing, incoming ->
    val events = Channel<InternalPuzzleEvent>(capacity = Channel.UNLIMITED)

    val coroutinePuzzleSubmissionFunction =
        AutoBatchedFunctionId(batchResumer = { batch ->
            events.send(InternalPuzzleEvent.ExpectationBatch(batch))
        })

    val runningTasks = ConcurrentHashMap<Long, Job>()

    try {
        withImportantCleanup {
            withLaunched(
                taskThatMustOutliveUsage = {
                    withLaunched(
                        taskThatMustOutliveUsage = {
                            try {
                                for (batch in incoming)
                                    events.send(InternalPuzzleEvent.SubmissionBatch(batch))
                            } finally {
                                importantCleanup {
                                    events.send(InternalPuzzleEvent.SubmissionBatch(null /* null means that submissions are over */))
                                }
                            }
                        },
                    ) {
                        @OptIn(ExperimentalTime::class)
                        coroutinePuzzleSubmissionFunction.autoBatchedOnQuiescence {
                            var exceptionFromExpectation: Throwable? = null
                            try {
                                context(
                                    object : CoroutinePuzzleBuilderScope {
                                        override suspend fun <T, R> expectCallTo(
                                            endPoint: CoroutinePuzzleEndPoint<T, R>,
                                            tSerializer: KSerializer<T>,
                                            rSerializer: KSerializer<R>,
                                            valueProducer: suspend (T) -> R,
                                        ): T {
                                            val (element, callId) = coroutinePuzzleSubmissionFunction.batched(
                                                InternalCoroutineExpectationMessage.Expectation(endPoint.id),
                                            ) as InternalCoroutineExpectationResult.MatchedSubmission
                                            return Json.decodeFromJsonElement(
                                                tSerializer,
                                                element,
                                            ).also { argument ->
                                                val task = this@autoBatchedOnQuiescence
                                                    .async { runCatching { valueProducer(argument) } }
                                                    .also { runningTasks[callId] = it }

                                                var exception: Throwable? = null
                                                val payload = try {
                                                    CoroutinePuzzleExpectationPayload.CallAnswered(
                                                        Json.encodeToJsonElement(
                                                            rSerializer,
                                                            task.await().getOrThrow(),
                                                        )
                                                    )
                                                } catch (t: Throwable) {
                                                    exception = t
                                                    if (task.isCancelled) CoroutinePuzzleExpectationPayload.CallCancellationCompleted
                                                    else CoroutinePuzzleExpectationPayload.CallThrew(t.message ?: "Unknown exception")
                                                }
                                                try {
                                                    coroutinePuzzleSubmissionFunction.batched(
                                                        InternalCoroutineExpectationMessage
                                                            .BatchEntry(WithCallId(callId, payload)),
                                                    )
                                                } catch (t: Throwable) {
                                                    exception?.addSuppressed(t) ?: throw t
                                                }
                                                exception?.let { throw it }
                                            }
                                        }

                                        override suspend fun awaitQuiescenceAndGetUnmatchedSubmissions(): List<CoroutinePuzzleEndPoint<*, *>> =
                                            (coroutinePuzzleSubmissionFunction.batched(
                                                InternalCoroutineExpectationMessage.AwaitQuiescence(finishExpectations = false),
                                            ) as InternalCoroutineExpectationResult.QuiescenceReached).unmatchedSubmissions
                                    }
                                ) {
                                    coroutineScope { builder() }
                                }
                            } catch (t: Throwable) {
                                exceptionFromExpectation = t
                            } finally {
                                try {
                                    coroutinePuzzleSubmissionFunction.batched(
                                        InternalCoroutineExpectationMessage.AwaitQuiescence(finishExpectations = true),
                                    )
                                } catch (e: Exception) {
                                    exceptionFromExpectation?.addSuppressed(e) ?: throw e
                                }
                                exceptionFromExpectation?.let { throw it }
                            }
                        }
                    }
                },
            ) {
                puzzleStateActor(
                    events,
                    emitBatch = {
                        outgoing.send(CoroutinePuzzleExpectationBatchOrCompletion.Batch(it))
                    },
                    runningTasks,
                    serverMetadata,
                )
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

private suspend fun puzzleStateActor(
    events: ReceiveChannel<InternalPuzzleEvent>,
    emitBatch: suspend (CoroutinePuzzleBatch<CoroutinePuzzleExpectationPayload>) -> Unit,
    runningTasks: ConcurrentHashMap<Long, Job>,
    serverMetadata: ServerMetadata,
): CoroutinePuzzleSolutionResult {
    val flowCallIds = mutableSetOf<Long>()
    val accumulatedExpectations = mutableListOf<CoroutinePuzzleEndPointWaitingState>()
    val accumulatedSubmissions = mutableListOf<WithCallId<CoroutinePuzzleSubmissionPayload.CallSubmitted>>()
    val quiescenceWaiters = mutableListOf<QuiescenceWaitingState>()
    var finishExpectationsWhenQuiescent = false
    val (firstExpectationAndResults, firstSubmission) = events.receiveFirstTwoEvents()
    var submissionsAndCancellations: List<WithCallId<CoroutinePuzzleSubmissionPayload>>? = firstSubmission
    var expectationAndResults: List<SuspendedBatchCall<InternalCoroutineExpectationMessage, InternalCoroutineExpectationResult?>> = firstExpectationAndResults

    while (true) {
        if (submissionsAndCancellations == null) {
            val expectedFollowups = expectationAndResults.map { it.query }
                .filterIsInstance<InternalCoroutineExpectationMessage.Expectation>()
                .map { it.endPoint }
                .plus(accumulatedExpectations.map { it.query.endPoint })
            val incomingQuiescenceWaiters = expectationAndResults
                .filter { it.query is InternalCoroutineExpectationMessage.AwaitQuiescence }
                .map { it.asQuiescenceWaitingState() }
            finishExpectationsWhenQuiescent = finishExpectationsWhenQuiescent || incomingQuiescenceWaiters.any {
                it.query.finishExpectations
            }
            val ordinaryQuiescenceWaiters = incomingQuiescenceWaiters.filterNot { it.query.finishExpectations }
            if (expectedFollowups.isEmpty() && ordinaryQuiescenceWaiters.isNotEmpty()) {
                val unmatchedSubmissions = accumulatedSubmissions.map {
                    serverMetadata.endpointFor(it.payload.endPoint)
                }.filterNot { serverMetadata.isFlowEndpoint(it.id) }
                ordinaryQuiescenceWaiters.resumeAllQuiescentTrackedScope {
                    it.continuation.resume(InternalCoroutineExpectationResult.QuiescenceReached(unmatchedSubmissions))
                }
                expectationAndResults = (events.receive() as InternalPuzzleEvent.ExpectationBatch).expectations
                continue
            }
            if (finishExpectationsWhenQuiescent && expectedFollowups.isEmpty()) {
                if (accumulatedSubmissions.isNotEmpty()) {
                    return MoreSubmissionsThanExpectationsFailure(
                        overshotSubmissions = accumulatedSubmissions.map { it.payload.endPoint },
                    )
                }
                return CoroutinePuzzleSolutionResult.Success
            }
            return MoreExpectationsThanSubmissionsFailure(
                expectedFollowups = expectedFollowups,
            )
        }

        if (
            expectationAndResults.isEmpty() &&
            submissionsAndCancellations.isEmpty() &&
            quiescenceWaiters.isEmpty()
        ) {
            return CoroutinePuzzleSolutionResult.FullyQuiescent
        }

        val newQuiescenceWaiters = expectationAndResults
            .filter { it.query is InternalCoroutineExpectationMessage.AwaitQuiescence }
            .map { it.asQuiescenceWaitingState() }
        finishExpectationsWhenQuiescent = finishExpectationsWhenQuiescent || newQuiescenceWaiters.any {
            it.query.finishExpectations
        }
        quiescenceWaiters += newQuiescenceWaiters.filterNot { it.query.finishExpectations }
        expectationAndResults = expectationAndResults
            .filter { it.query !is InternalCoroutineExpectationMessage.AwaitQuiescence }

        val (newExpectations, results) = expectationAndResults.partitionExpectationsAndResults()
        expectationAndResults = expectationAndResults.filter { it.query !is InternalCoroutineExpectationMessage.Expectation }
        val (newSubmissions, cancellations) = submissionsAndCancellations.partitionSubmissionsAndCancellations()
        submissionsAndCancellations = submissionsAndCancellations.filter { it.payload !is CoroutinePuzzleSubmissionPayload.CallSubmitted }
        accumulatedExpectations.addAll(newExpectations)
        accumulatedSubmissions.addAll(newSubmissions)

        if (results.isEmpty()) {
            val matches = accumulatedSubmissions.mapNotNull { submission ->
                accumulatedExpectations
                    .lastOrNull { it.query.endPoint == submission.payload.endPoint }
                    // Make sure we don't process the same expectation twice
                    ?.also { accumulatedExpectations.remove(it) }
                    ?.to(submission)
            }.onEach { accumulatedSubmissions.remove(it.second) }

            if (matches.isEmpty() && cancellations.isEmpty()) {
                if (finishExpectationsWhenQuiescent) {
                    if (accumulatedExpectations.isNotEmpty()) {
                        return MoreExpectationsThanSubmissionsFailure(
                            expectedFollowups = accumulatedExpectations.map { it.query.endPoint },
                        )
                    }
                    if (accumulatedSubmissions.isNotEmpty()) {
                        return MoreSubmissionsThanExpectationsFailure(
                            overshotSubmissions = accumulatedSubmissions.map { it.payload.endPoint },
                        )
                    }
                    return CoroutinePuzzleSolutionResult.Success
                }
                if (quiescenceWaiters.isEmpty()) {
                    val unexpectedIds = accumulatedSubmissions.map { it.payload.endPoint }
                    failInternal(CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
                        unexpectedSubmissions = unexpectedIds
                            .filterNot(serverMetadata::isFlowEndpoint)
                            .ifEmpty { unexpectedIds },
                        expectations = accumulatedExpectations.map { it.query.endPoint },
                    ))
                }

                val unmatchedSubmissions = accumulatedSubmissions.map {
                    serverMetadata.endpointFor(it.payload.endPoint)
                }.filterNot { serverMetadata.isFlowEndpoint(it.id) }
                // This starts an expectation-side turn. Do not resume submissions here.
                quiescenceWaiters.resumeAllQuiescentTrackedScope {
                    it.continuation.resume(InternalCoroutineExpectationResult.QuiescenceReached(unmatchedSubmissions))
                }
                quiescenceWaiters.clear()
                expectationAndResults = (events.receive() as InternalPuzzleEvent.ExpectationBatch).expectations
                continue
            }

            (matches.map { it.second } + accumulatedSubmissions)
                .filter { serverMetadata.isFlowEndpoint(it.payload.endPoint) }
                .forEach { flowCallIds += it.callId }
            matches.firstOrNull()?.first?.continuation?.runOnScopeThatTracksQuiescence {
                cancellations.forEach { cancelRunningTask(it.callId, runningTasks, flowCallIds) }

                matches.forEach { (expectation, submission) ->
                    expectation.continuation.resume(
                        InternalCoroutineExpectationResult.MatchedSubmission(submission.payload.arg, submission.callId),
                    )
                }
            }   // Hmm sadly technically the following still has the race condition that runOnScopeThatTracksQuiescence tries to solve :(
                ?: cancellations.forEach { cancelRunningTask(it.callId, runningTasks, flowCallIds) }
            submissionsAndCancellations = submissionsAndCancellations.filter { it.payload !is CoroutinePuzzleSubmissionPayload.CallShouldCancel }

            expectationAndResults = (events.receive() as InternalPuzzleEvent.ExpectationBatch).expectations
        } else {
            results.resumeAllQuiescentTrackedScope { it.continuation.resume(null) }
            expectationAndResults = (events.receive() as InternalPuzzleEvent.ExpectationBatch).expectations

            emitBatch(results.map { it.query.reply.also { reply -> runningTasks.remove(reply.callId) } })

            submissionsAndCancellations = (events.receive() as InternalPuzzleEvent.SubmissionBatch).submissions
        }
    }
}

private fun cancelRunningTask(callId: Long, runningTasks: Map<Long, Job>, flowCallIds: Set<Long>) {
    val task = runningTasks[callId]
    if (task == null && callId !in flowCallIds) failInternal(CustomFailure(
        "Unexpected cancellation for call $callId: its expectation was not running.",
    ))
    task?.cancel(CancellationAcrossRpc())
}

private fun List<WithCallId<CoroutinePuzzleSubmissionPayload>>.partitionSubmissionsAndCancellations(): Pair<
    List<WithCallId<CoroutinePuzzleSubmissionPayload.CallSubmitted>>,
    List<WithCallId<CoroutinePuzzleSubmissionPayload.CallShouldCancel>>,
> {
    @Suppress("UNCHECKED_CAST")
    return this.partition { it.payload is CoroutinePuzzleSubmissionPayload.CallSubmitted } as Pair<
        List<WithCallId<CoroutinePuzzleSubmissionPayload.CallSubmitted>>,
        List<WithCallId<CoroutinePuzzleSubmissionPayload.CallShouldCancel>>,
    >
}

private typealias CoroutinePuzzleEndPointWaitingState = SuspendedBatchCall<InternalCoroutineExpectationMessage.Expectation, InternalCoroutineExpectationResult.MatchedSubmission>
private typealias QuiescenceWaitingState = SuspendedBatchCall<InternalCoroutineExpectationMessage.AwaitQuiescence, InternalCoroutineExpectationResult.QuiescenceReached>

@Suppress("UNCHECKED_CAST")
private fun SuspendedBatchCall<InternalCoroutineExpectationMessage, InternalCoroutineExpectationResult?>.asQuiescenceWaitingState(): QuiescenceWaitingState =
    this as QuiescenceWaitingState

private fun List<SuspendedBatchCall<InternalCoroutineExpectationMessage, InternalCoroutineExpectationResult?>>.partitionExpectationsAndResults(
): Pair<
    List<SuspendedBatchCall<InternalCoroutineExpectationMessage.Expectation, InternalCoroutineExpectationResult.MatchedSubmission>>,
    List<SuspendedBatchCall<InternalCoroutineExpectationMessage.BatchEntry, Nothing?>>,
> {
    @Suppress("UNCHECKED_CAST")
    return this.partition { it.query is InternalCoroutineExpectationMessage.Expectation } as Pair<
        List<SuspendedBatchCall<InternalCoroutineExpectationMessage.Expectation, InternalCoroutineExpectationResult.MatchedSubmission>>,
        List<SuspendedBatchCall<InternalCoroutineExpectationMessage.BatchEntry, Nothing?>>,
    >
}

private suspend fun ReceiveChannel<InternalPuzzleEvent>.receiveFirstTwoEvents(
): Pair<List<SuspendedBatchCall<InternalCoroutineExpectationMessage, InternalCoroutineExpectationResult?>>, List<WithCallId<CoroutinePuzzleSubmissionPayload>>?> {
    val lhs = receive()
    val rhs = receive()
    return when (lhs) {
        is InternalPuzzleEvent.ExpectationBatch if (rhs is InternalPuzzleEvent.SubmissionBatch) ->
            lhs.expectations to rhs.submissions
        is InternalPuzzleEvent.SubmissionBatch if (rhs is InternalPuzzleEvent.ExpectationBatch) ->
            rhs.expectations to lhs.submissions
        else -> throw IllegalStateException("First 2 events must be expectation and submission")
    }
}

private sealed class InternalCoroutineExpectationMessage {
    data class Expectation(val endPoint: CoroutinePuzzleEndPointId): InternalCoroutineExpectationMessage()
    data class BatchEntry(val reply: WithCallId<CoroutinePuzzleExpectationPayload>): InternalCoroutineExpectationMessage()
    data class AwaitQuiescence(val finishExpectations: Boolean) : InternalCoroutineExpectationMessage()
}

private sealed class InternalCoroutineExpectationResult {
    data class MatchedSubmission(val element: JsonElement, val callId: Long) : InternalCoroutineExpectationResult()
    data class QuiescenceReached(
        val unmatchedSubmissions: List<CoroutinePuzzleEndPoint<*, *>>,
    ) : InternalCoroutineExpectationResult()
}

private sealed class InternalPuzzleEvent {
    data class SubmissionBatch(
        /** Null means submissions are done */
        val submissions: List<WithCallId<CoroutinePuzzleSubmissionPayload>>?,
    ): InternalPuzzleEvent()
    data class ExpectationBatch(
        val expectations: List<SuspendedBatchCall<InternalCoroutineExpectationMessage, InternalCoroutineExpectationResult?>>,
    ): InternalPuzzleEvent()
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

context(builder: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint</* @Exact */T, /* @Exact */R>.expectCall(
    noinline valueProducer: suspend (T) -> R,
): T = builder.expectCallTo(this, serializer(), serializer(), valueProducer)

context(_: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint<T, R>.expectThrowingCall(message: String) {
    try {
        expectCall { throw ExpectedCallException(message) }
    } catch (failure: ExpectedCallException) {
        if (failure.message != message) throw failure
    }
}

@PublishedApi
internal class ExpectedCallException(message: String) : Exception(message)

context(builder: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint</* @Exact */T, /* @Exact */R>.expectCanceledCall(
    noinline valueProducer: suspend (T) -> Nothing,
): CancellationException {
    try {
        expectCall(valueProducer)
    } catch (t: CancellationException) {
        return t
    }
    fail("CancellationException was expected")
}

context(builder: CoroutinePuzzleBuilderScope)
suspend fun awaitQuiescenceAndGetUnmatchedSubmissions(): List<CoroutinePuzzleEndPoint<*, *>> =
    builder.awaitQuiescenceAndGetUnmatchedSubmissions()

/**
 * When the submissions aren't matched, this function is invoked to provide a custom error message.
 * If the function is null, or the message returned a null, then the default error message is used.
 */
typealias UnmatchedSubmissionMessageFunction = ((List<CoroutinePuzzleEndPoint<*, *>>) -> String?)

context(_: CoroutinePuzzleBuilderScope)
fun verifyUnmatchedSubmissions(
    submissions: List<CoroutinePuzzleEndPoint<*, *>>,
    expected: List<CoroutinePuzzleEndPoint<*, *>>,
    message: UnmatchedSubmissionMessageFunction? = null,
) {
    if (submissions.groupingBy { it.id }.eachCount() != expected.groupingBy { it.id }.eachCount()) {
        message?.invoke(submissions)?.let { fail(it) }
        fail(CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure(
            submissions = submissions.map { it.id },
            expectations = expected.map { it.id },
        ))
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

context(_: CoroutinePuzzleBuilderScope)
inline fun <reified A, reified T> CoroutinePuzzleEndPoint<WithCallId<A>, WithCallId<ValueOrCompletion<T>>>.expectingFlowCollector(
): Resource<Resource<Pair<A, suspend (T) -> Unit>>> = createExpectedFlowCollectors(serializer(), serializer())

@PublishedApi
context(_: CoroutinePuzzleBuilderScope)
internal fun <A, T> CoroutinePuzzleEndPoint<WithCallId<A>, WithCallId<ValueOrCompletion<T>>>.createExpectedFlowCollectors(
    argumentSerializer: KSerializer<WithCallId<A>>,
    resultSerializer: KSerializer<WithCallId<ValueOrCompletion<T>>>,
): Resource<Resource<Pair<A, suspend (T) -> Unit>>> = resource { consume ->
        val registrations = Channel<ExpectedFlowCollector<A, T>>(Channel.UNLIMITED)
        val callsNeeded = Channel<Unit>(Channel.UNLIMITED)
        val matcher = launch { matchFlowCollectors(registrations, callsNeeded, argumentSerializer, resultSerializer) }
        try {
            consume(resource { consumeCollector ->
                val collector = ExpectedFlowCollector<A, T>(callsNeeded)
                registrations.send(collector)
                callsNeeded.send(Unit)
                try {
                    try {
                        consumeCollector(collector.argument.await() to { value -> collector.send(FlowCollectorEvent.Value(value)) })
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        collector.send(FlowCollectorEvent.Failure(failure))
                        throw failure
                    }.also {
                        collector.send(FlowCollectorEvent.Completion())
                    }
                } finally {
                    collector.events.cancel()
                }
            })
        } finally {
            matcher.cancel()
        }
    }

context(builder: CoroutinePuzzleBuilderScope)
internal suspend fun <A, T> CoroutinePuzzleEndPoint<WithCallId<A>, WithCallId<ValueOrCompletion<T>>>.matchFlowCollectors(
    registrations: ReceiveChannel<ExpectedFlowCollector<A, T>>,
    callsNeeded: ReceiveChannel<Unit>,
    argumentSerializer: KSerializer<WithCallId<A>>,
    resultSerializer: KSerializer<WithCallId<ValueOrCompletion<T>>>,
) {
    val collectors = mutableMapOf<Long, ExpectedFlowCollector<A, T>>()
    coroutineScope {
        for (ignored in callsNeeded) {
            launch {
                var submittedCollectorId: Long? = null
                var event: FlowCollectorEvent<T>? = null
                try {
                    builder.expectCallTo(this@matchFlowCollectors, argumentSerializer, resultSerializer) { submitted ->
                        submittedCollectorId = submitted.callId
                        val collector = collectors[submitted.callId] ?: registrations.receive().also {
                            it.collectorId = submitted.callId
                            it.argument.complete(submitted.payload)
                            collectors[submitted.callId] = it
                        }
                        val received = collector.events.receive()
                        event = received
                        when (received) {
                            is FlowCollectorEvent.Value -> WithCallId(submitted.callId, ValueOrCompletion.Value(received.value))
                            is FlowCollectorEvent.Completion -> WithCallId(submitted.callId, ValueOrCompletion.Completion)
                            is FlowCollectorEvent.Failure -> throw received.failure
                        }
                    }
                } catch (cancellation: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    collectors.remove(submittedCollectorId)?.events?.cancel(cancellation)
                    return@launch
                } catch (failure: Throwable) {
                    if ((event as? FlowCollectorEvent.Failure)?.failure !== failure) throw failure
                }

                event!!.sent.complete(Unit)
                if (event !is FlowCollectorEvent.Value) collectors.remove(submittedCollectorId)
            }
        }
    }
}

internal class ExpectedFlowCollector<A, T>(private val callsNeeded: Channel<Unit>) {
    var collectorId: Long? = null
    val argument = CompletableDeferred<A>()
    val events = Channel<FlowCollectorEvent<T>>(Channel.RENDEZVOUS)

    suspend fun send(event: FlowCollectorEvent<T>) {
        if (events.trySend(event).isSuccess) {
            event.sent.await()
            return
        }
        callsNeeded.send(Unit)
        events.send(event)
        event.sent.await()
    }
}

internal sealed interface FlowCollectorEvent<out T> {
    val sent: CompletableDeferred<Unit>

    data class Value<T>(val value: T, override val sent: CompletableDeferred<Unit> = CompletableDeferred()) : FlowCollectorEvent<T>
    data class Completion(override val sent: CompletableDeferred<Unit> = CompletableDeferred()) : FlowCollectorEvent<Nothing>
    data class Failure(
        val failure: Throwable,
        override val sent: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : FlowCollectorEvent<Nothing>
}
