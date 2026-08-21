package kmpworkshop.server

import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleExpectationPayload
import kmpworkshop.common.CoroutinePuzzleSolutionResult.CustomFailure
import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure
import kmpworkshop.common.CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure
import kmpworkshop.server.InternalPuzzleEvent.ExpectationBatch
import kmpworkshop.server.InternalPuzzleEvent.SubmissionBatch
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

/** Typed server-side marker, converted to a serialized [JsonElement] before it enters the protocol result. */
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
            events.send(ExpectationBatch(batch))
        })

    val runningTasks = ConcurrentHashMap<Long, Job>()

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
                        coroutinePuzzleSubmissionFunction.autoBatchedOnQuiescence {
                            var exceptionFromExpectation: Throwable? = null
                            try {
                                context(
                                    object : CoroutinePuzzleBuilderScope {
                                        override suspend fun <T, R> expectCallTo(
                                            endPoint: CoroutinePuzzleEndPoint<T, R>,
                                            tSerializer: KSerializer<T>,
                                            rSerializer: KSerializer<R>,
                                            expectedArgument: ExpectedArgument<T>,
                                            valueProducer: suspend context(CoroutinePuzzleValueProducerScope) CoroutineScope.(T) -> R,
                                        ): T {
                                            val (element, callId) = coroutinePuzzleSubmissionFunction.batched(
                                                InternalCoroutineExpectationMessage.Expectation(
                                                    CoroutinePuzzleExpectedFollowup(
                                                        endPoint.id,
                                                        expectedArgument.encodeWith(tSerializer),
                                                    ),
                                                ),
                                            ) as InternalCoroutineExpectationResult.MatchedSubmission
                                            return Json.decodeFromJsonElement(
                                                tSerializer,
                                                element,
                                            ).also { argument ->
                                                val valueProducerScope = object : CoroutinePuzzleValueProducerScope {
                                                    override suspend fun expectCancellation(): Nothing {
                                                        coroutinePuzzleSubmissionFunction.batched(
                                                            InternalCoroutineExpectationMessage.CancellationExpectation(
                                                                CoroutinePuzzleExpectedFollowup(
                                                                    endPoint = endPoint.id,
                                                                    expectedCancellationOfCallId = callId,
                                                                ),
                                                            ),
                                                        )
                                                        awaitCancellation()
                                                    }
                                                }
                                                val task = this@autoBatchedOnQuiescence
                                                    .async {
                                                        runCatching {
                                                            context(valueProducerScope) {
                                                                coroutineScope { valueProducer(argument) }
                                                            }
                                                        }
                                                    }
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

private typealias RawExpectationBatch =
    List<SuspendedBatchCall<InternalCoroutineExpectationMessage, InternalCoroutineExpectationResult?>>
private typealias RawSubmissionBatch = List<WithCallId<CoroutinePuzzleSubmissionPayload>>

private suspend fun ReceiveChannel<InternalPuzzleEvent>.receiveInitialBatches(
): Pair<RawExpectationBatch, RawSubmissionBatch?> {
    val first = receive()
    return when (val second = receive()) {
        is SubmissionBatch if first is ExpectationBatch -> first.expectations to second.submissions
        is ExpectationBatch if first is SubmissionBatch -> second.expectations to first.submissions
        else -> error("The first two puzzle events must contain one expectation batch and one submission batch.")
    }
}

private suspend fun ReceiveChannel<InternalPuzzleEvent>.receiveExpectationBatch(): RawExpectationBatch =
    when (val event = receive()) {
        is ExpectationBatch -> event.expectations
        is SubmissionBatch -> error("Expected an expectation batch, but received a submission batch.")
    }

private suspend fun ReceiveChannel<InternalPuzzleEvent>.receiveSubmissionBatch(): RawSubmissionBatch? =
    when (val event = receive()) {
        is SubmissionBatch -> event.submissions
        is ExpectationBatch -> error("Expected a submission batch, but received an expectation batch.")
    }

/**
 * Bounces quiescence between the evaluator and solution sides.
 *
 * Resuming an evaluator-side continuation always produces the next [InternalPuzzleEvent.ExpectationBatch]. Only
 * emitting completed results resumes the solution side and produces the next [InternalPuzzleEvent.SubmissionBatch].
 * The explicit receive functions below assert this alternation; individual message kinds are accumulated in
 * [PuzzleActorState].
 */
private suspend fun puzzleStateActor(
    events: ReceiveChannel<InternalPuzzleEvent>,
    emitBatch: suspend (CoroutinePuzzleBatch<CoroutinePuzzleExpectationPayload>) -> Unit,
    runningTasks: ConcurrentHashMap<Long, Job>,
    serverMetadata: ServerMetadata,
): CoroutinePuzzleSolutionResult {
    val state = PuzzleActorState(serverMetadata)
    val (expectations, submissions) = events.receiveInitialBatches()
    state.accept(expectations.classify())
    var submissionsClosed = submissions == null
    submissions?.let { state.accept(it.classify()) }

    while (true) {
        if (submissionsClosed) {
            val expectedFollowups = state.expectedFollowups()
            if (expectedFollowups.isEmpty() && state.hasQuiescenceWaiters()) {
                state.resumeQuiescenceWaiters()
                state.accept(events.receiveExpectationBatch().classify())
                continue
            }
            if (state.finishExpectationsWhenQuiescent && expectedFollowups.isEmpty()) {
                return state.submissions.takeIf { it.isNotEmpty() }
                    ?.let { MoreSubmissionsThanExpectationsFailure(it.map { call -> call.payload.endPoint }) }
                    ?: CoroutinePuzzleSolutionResult.Success
            }
            return MoreExpectationsThanSubmissionsFailure(expectedFollowups)
        }

        val results = state.takeResults()
        if (results.isNotEmpty()) {
            results.resumeAllQuiescentTrackedScope { it.continuation.resume(null) }
            state.accept(events.receiveExpectationBatch().classify())

            emitBatch(results.map { it.query.reply.also { reply -> runningTasks.remove(reply.callId) } })
            val submissions = events.receiveSubmissionBatch()
            submissionsClosed = submissions == null
            submissions?.let { state.accept(it.classify()) }
            continue
        }

        val matches = state.takeMatches()
        val cancellations = state.takeCancellationRequests()
        if (matches.isNotEmpty() || cancellations.isNotEmpty()) {
            resumeMatchesAndCancellations(state, matches, cancellations, runningTasks)
            state.accept(events.receiveExpectationBatch().classify())
            continue
        }

        if (state.finishExpectationsWhenQuiescent) {
            val expectedFollowups = state.expectedFollowups()
            return when {
                expectedFollowups.isNotEmpty() -> MoreExpectationsThanSubmissionsFailure(expectedFollowups)
                state.submissions.isNotEmpty() -> MoreSubmissionsThanExpectationsFailure(
                    overshotSubmissions = state.submissions.map { it.payload.endPoint },
                )
                else -> CoroutinePuzzleSolutionResult.Success
            }
        }

        if (state.hasQuiescenceWaiters()) {
            state.resumeQuiescenceWaiters()
            state.accept(events.receiveExpectationBatch().classify())
            continue
        }

        if (state.isFullyQuiescent()) return CoroutinePuzzleSolutionResult.FullyQuiescent

        val unexpectedIds = state.submissions.map { it.payload.endPoint }
        failInternal(CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
            unexpectedSubmissions = unexpectedIds
                .filterNot(serverMetadata::isFlowEndpoint)
                .ifEmpty { unexpectedIds },
            expectations = state.expectedFollowups(),
        ))
    }
}

private fun cancelRunningTask(callId: Long, runningTasks: Map<Long, Job>, flowCallIds: Set<Long>) {
    val task = runningTasks[callId]
    if (task == null && callId !in flowCallIds) failInternal(CustomFailure(
        "Unexpected cancellation for call $callId: its expectation was not running.",
    ))
    task?.cancel(CancellationAcrossRpc())
}

private typealias CoroutinePuzzleCallMatch = Pair<
    CoroutinePuzzleEndPointWaitingState,
    WithCallId<CoroutinePuzzleSubmissionPayload.CallSubmitted>,
>

private class PuzzleActorState(private val serverMetadata: ServerMetadata) {
    val expectations = mutableListOf<CoroutinePuzzleEndPointWaitingState>()
    val cancellationExpectations = mutableListOf<CoroutinePuzzleCancellationWaitingState>()
    val submissions = mutableListOf<WithCallId<CoroutinePuzzleSubmissionPayload.CallSubmitted>>()
    val flowCallIds = mutableSetOf<Long>()

    private val cancellationRequests = mutableListOf<WithCallId<CoroutinePuzzleSubmissionPayload.CallShouldCancel>>()
    private val results = mutableListOf<CoroutinePuzzleBatchEntryWaitingState>()
    private val quiescenceWaiters = mutableListOf<QuiescenceWaitingState>()

    var finishExpectationsWhenQuiescent = false
        private set

    fun accept(batch: ClassifiedExpectationBatch) {
        expectations += batch.expectations
        cancellationExpectations += batch.cancellationExpectations
        results += batch.results
        finishExpectationsWhenQuiescent = finishExpectationsWhenQuiescent ||
            batch.quiescenceWaiters.any { it.query.finishExpectations }
        quiescenceWaiters += batch.quiescenceWaiters.filterNot { it.query.finishExpectations }
    }

    fun accept(batch: ClassifiedSubmissionBatch) {
        submissions += batch.submissions
        cancellationRequests += batch.cancellations
        batch.submissions
            .filter { serverMetadata.isFlowEndpoint(it.payload.endPoint) }
            .forEach { flowCallIds += it.callId }
    }

    fun expectedFollowups(): List<CoroutinePuzzleExpectedFollowup> =
        expectations.map { it.query.expectedFollowup } +
            cancellationExpectations.map { it.query.expectedFollowup }

    fun takeMatches(): List<CoroutinePuzzleCallMatch> = buildList {
        val submissionIterator = submissions.listIterator()
        while (submissionIterator.hasNext()) {
            val submission = submissionIterator.next()
            val expectationIndex = expectations.indexOfLast {
                it.query.expectedFollowup.endPoint == submission.payload.endPoint
            }
            if (expectationIndex >= 0) {
                add(expectations.removeAt(expectationIndex) to submission)
                submissionIterator.remove()
            }
        }
    }

    fun takeCancellationRequests(): List<WithCallId<CoroutinePuzzleSubmissionPayload.CallShouldCancel>> =
        cancellationRequests.toList().also { cancellationRequests.clear() }

    fun takeResults(): List<CoroutinePuzzleBatchEntryWaitingState> =
        results.toList().also { results.clear() }

    fun removeCancellationExpectationsFor(callIds: Set<Long>) {
        cancellationExpectations.removeAll {
            it.query.expectedFollowup.expectedCancellationOfCallId in callIds
        }
    }

    fun hasQuiescenceWaiters(): Boolean = quiescenceWaiters.isNotEmpty()

    suspend fun resumeQuiescenceWaiters() {
        val unmatchedSubmissions = submissions.map {
            serverMetadata.endpointFor(it.payload.endPoint)
        }.filterNot { serverMetadata.isFlowEndpoint(it.id) }
        quiescenceWaiters.toList().also { quiescenceWaiters.clear() }
            .resumeAllQuiescentTrackedScope {
                it.continuation.resume(InternalCoroutineExpectationResult.QuiescenceReached(unmatchedSubmissions))
            }
    }

    fun isFullyQuiescent(): Boolean =
        expectations.isEmpty() &&
            cancellationExpectations.isEmpty() &&
            submissions.isEmpty() &&
            cancellationRequests.isEmpty() &&
            results.isEmpty() &&
            quiescenceWaiters.isEmpty()
}

private suspend fun resumeMatchesAndCancellations(
    state: PuzzleActorState,
    matches: List<CoroutinePuzzleCallMatch>,
    cancellations: List<WithCallId<CoroutinePuzzleSubmissionPayload.CallShouldCancel>>,
    runningTasks: Map<Long, Job>,
) {
    state.removeCancellationExpectationsFor(
        cancellations.mapTo(mutableSetOf()) { it.callId },
    )
    val anchor = matches.firstOrNull()?.first?.continuation

    if (anchor == null) {
        check(matches.isEmpty())
        cancellations.forEach { cancelRunningTask(it.callId, runningTasks, state.flowCallIds) }
        return
    }

    anchor.runOnScopeThatTracksQuiescence {
        cancellations.forEach { cancelRunningTask(it.callId, runningTasks, state.flowCallIds) }
        matches.forEach { (expectation, submission) ->
            expectation.continuation.resume(
                InternalCoroutineExpectationResult.MatchedSubmission(submission.payload.arg, submission.callId),
            )
        }
    }
}

private typealias CoroutinePuzzleEndPointWaitingState = SuspendedBatchCall<InternalCoroutineExpectationMessage.Expectation, InternalCoroutineExpectationResult.MatchedSubmission>
private typealias CoroutinePuzzleCancellationWaitingState = SuspendedBatchCall<InternalCoroutineExpectationMessage.CancellationExpectation, InternalCoroutineExpectationResult?>
private typealias CoroutinePuzzleBatchEntryWaitingState = SuspendedBatchCall<InternalCoroutineExpectationMessage.BatchEntry, Nothing?>
private typealias QuiescenceWaitingState = SuspendedBatchCall<InternalCoroutineExpectationMessage.AwaitQuiescence, InternalCoroutineExpectationResult.QuiescenceReached>

private data class ClassifiedExpectationBatch(
    val expectations: List<CoroutinePuzzleEndPointWaitingState>,
    val cancellationExpectations: List<CoroutinePuzzleCancellationWaitingState>,
    val results: List<CoroutinePuzzleBatchEntryWaitingState>,
    val quiescenceWaiters: List<QuiescenceWaitingState>,
)

private fun RawExpectationBatch.classify(): ClassifiedExpectationBatch {
    val expectations = mutableListOf<CoroutinePuzzleEndPointWaitingState>()
    val cancellationExpectations = mutableListOf<CoroutinePuzzleCancellationWaitingState>()
    val results = mutableListOf<CoroutinePuzzleBatchEntryWaitingState>()
    val quiescenceWaiters = mutableListOf<QuiescenceWaitingState>()
    forEach { entry ->
        @Suppress("UNCHECKED_CAST")
        when (entry.query) {
            is InternalCoroutineExpectationMessage.Expectation -> expectations += entry as CoroutinePuzzleEndPointWaitingState
            is InternalCoroutineExpectationMessage.CancellationExpectation ->
                cancellationExpectations += entry as CoroutinePuzzleCancellationWaitingState
            is InternalCoroutineExpectationMessage.BatchEntry -> results += entry as CoroutinePuzzleBatchEntryWaitingState
            is InternalCoroutineExpectationMessage.AwaitQuiescence -> quiescenceWaiters += entry as QuiescenceWaitingState
        }
    }
    return ClassifiedExpectationBatch(
        expectations,
        cancellationExpectations,
        results,
        quiescenceWaiters,
    )
}

private data class ClassifiedSubmissionBatch(
    val submissions: List<WithCallId<CoroutinePuzzleSubmissionPayload.CallSubmitted>>,
    val cancellations: List<WithCallId<CoroutinePuzzleSubmissionPayload.CallShouldCancel>>,
)

private fun RawSubmissionBatch.classify(): ClassifiedSubmissionBatch {
    val submissions = mutableListOf<WithCallId<CoroutinePuzzleSubmissionPayload.CallSubmitted>>()
    val cancellations = mutableListOf<WithCallId<CoroutinePuzzleSubmissionPayload.CallShouldCancel>>()
    forEach { entry ->
        when (val payload = entry.payload) {
            is CoroutinePuzzleSubmissionPayload.CallSubmitted -> submissions += WithCallId(entry.callId, payload)
            CoroutinePuzzleSubmissionPayload.CallShouldCancel ->
                cancellations += WithCallId(entry.callId, CoroutinePuzzleSubmissionPayload.CallShouldCancel)
        }
    }
    return ClassifiedSubmissionBatch(submissions, cancellations)
}

private sealed class InternalCoroutineExpectationMessage {
    data class Expectation(val expectedFollowup: CoroutinePuzzleExpectedFollowup) : InternalCoroutineExpectationMessage()
    data class CancellationExpectation(val expectedFollowup: CoroutinePuzzleExpectedFollowup) : InternalCoroutineExpectationMessage()
    data class BatchEntry(val reply: WithCallId<CoroutinePuzzleExpectationPayload>): InternalCoroutineExpectationMessage()
    data class AwaitQuiescence(val finishExpectations: Boolean) : InternalCoroutineExpectationMessage()
}

private sealed class InternalCoroutineExpectationResult {
    data class MatchedSubmission(val element: JsonElement, val callId: Long) : InternalCoroutineExpectationResult()
    data class QuiescenceReached(
        val unmatchedSubmissions: List<CoroutinePuzzleEndPoint<*, *>>,
    ) : InternalCoroutineExpectationResult()
}

private sealed interface InternalPuzzleEvent {
    data class SubmissionBatch(
        /** Null means submissions are done. */
        val submissions: RawSubmissionBatch?,
    ) : InternalPuzzleEvent

    data class ExpectationBatch(val expectations: RawExpectationBatch) : InternalPuzzleEvent
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
    noinline valueProducer: suspend context(CoroutinePuzzleValueProducerScope) CoroutineScope.(T) -> Nothing,
): CancellationException {
    try {
        builder.expectCallTo(this, serializer(), serializer(), valueProducer = valueProducer)
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
        val incorrectSubmissions = submissions.incorrectComparedTo(expected)
        message?.invoke(submissions)?.let { fail(it, incorrectSubmissions) }
        fail(CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure(
            submissions = submissions.map { it.id },
            expectations = expected.map { CoroutinePuzzleExpectedFollowup(it.id) },
            incorrectSubmissions = incorrectSubmissions.map { it.id },
        ))
    }
}

/** The remaining submissions after pairing each expected endpoint with one actual submission. */
private fun List<CoroutinePuzzleEndPoint<*, *>>.incorrectComparedTo(
    expected: List<CoroutinePuzzleEndPoint<*, *>>,
): List<CoroutinePuzzleEndPoint<*, *>> {
    val remainingExpectedCounts = expected.groupingBy { it.id }.eachCount().toMutableMap()
    return filter { submission ->
        val remaining = remainingExpectedCounts[submission.id] ?: 0
        if (remaining == 0) {
            true
        } else {
            remainingExpectedCounts[submission.id] = remaining - 1
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
        val callsNeeded = Channel<ExpectedFlowCollector<A, T>?>(Channel.UNLIMITED)
        val matcher = launch { matchFlowCollectors(registrations, callsNeeded, argumentSerializer, resultSerializer) }
        try {
            consume(resource { consumeCollector ->
                val collector = ExpectedFlowCollector(callsNeeded)
                registrations.send(collector)
                callsNeeded.send(null)
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
    callsNeeded: ReceiveChannel<ExpectedFlowCollector<A, T>?>,
    argumentSerializer: KSerializer<WithCallId<A>>,
    resultSerializer: KSerializer<WithCallId<ValueOrCompletion<T>>>,
) {
    val collectors = mutableMapOf<Long, ExpectedFlowCollector<A, T>>()
    coroutineScope {
        for (expectedCollector in callsNeeded) {
            launch {
                var submittedCollectorId: Long? = null
                var event: FlowCollectorEvent<T>? = null
                try {
                    builder.expectCallTo(
                        this@matchFlowCollectors,
                        argumentSerializer,
                        resultSerializer,
                        expectedArgument = expectedCollector?.submittedArgument?.let { ExpectedArgument.Exact(it) }
                            ?: ExpectedArgument.None,
                    ) { submitted ->
                        submittedCollectorId = submitted.callId
                        val collector = collectors[submitted.callId] ?: registrations.receive().also {
                            it.submittedArgument = submitted
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

internal class ExpectedFlowCollector<A, T>(
    private val callsNeeded: Channel<ExpectedFlowCollector<A, T>?>,
) {
    var submittedArgument: WithCallId<A>? = null
    val argument = CompletableDeferred<A>()
    val events = Channel<FlowCollectorEvent<T>>(Channel.RENDEZVOUS)

    suspend fun send(event: FlowCollectorEvent<T>) {
        if (events.trySend(event).isSuccess) {
            event.sent.await()
            return
        }
        callsNeeded.send(this)
        events.send(event)
        event.sent.await()
    }
}

private fun <T> ExpectedArgument<T>.encodeWith(serializer: KSerializer<T>): JsonElement? = when (this) {
    ExpectedArgument.None -> null
    is ExpectedArgument.Exact -> Json.encodeToJsonElement(serializer, value)
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
