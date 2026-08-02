package kmpworkshop.server

import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleBatchEntry.ExpectationPayload
import kmpworkshop.common.CoroutinePuzzleBatchEntry.SubmissionPayload
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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime

interface CoroutinePuzzleBuilderScope {
    suspend fun <T, R> expectCallTo(
        endPoint: CoroutinePuzzleEndPoint<T, R>,
        tSerializer: KSerializer<T>,
        rSerializer: KSerializer<R>,
        valueProducer: suspend (T) -> R,
    ): T

    suspend fun <T> expectingMatchedParallelism(block: suspend CoroutineScope.() -> T): T
}

private object InStrictParallelismExpectation : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<InStrictParallelismExpectation>
}

fun coroutinePuzzle(
    builder: suspend context(CoroutinePuzzleBuilderScope) CoroutineScope.() -> Unit,
): CoroutinePuzzle = coroutinePuzzleCommunicationChannel { outgoing, incoming ->
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
                                                InternalCoroutineExpectationMessage.Expectation(
                                                    CoroutinePuzzleEndPointExpectation(
                                                        endPoint = endPoint.descriptor,
                                                        isStrictParallelism = currentCoroutineContext()[InStrictParallelismExpectation.Key] != null,
                                                    ),
                                                ),
                                            )!!
                                            return Json.decodeFromJsonElement(
                                                tSerializer,
                                                element,
                                            ).also { argument ->
                                                val task = this@autoBatchedOnQuiescence
                                                    .async { runCatching { valueProducer(argument) } }
                                                    .also { runningTasks[callId] = it }

                                                var exception: Throwable? = null
                                                val payload = try {
                                                    ExpectationPayload.CallAnswered(
                                                        Json.encodeToJsonElement(
                                                            rSerializer,
                                                            task.await().getOrThrow(),
                                                        )
                                                    )
                                                } catch (t: Throwable) {
                                                    exception = t
                                                    if (task.isCancelled) ExpectationPayload.CallCancellationCompleted
                                                    else ExpectationPayload.CallThrew
                                                }
                                                try {
                                                    coroutinePuzzleSubmissionFunction.batched(
                                                        InternalCoroutineExpectationMessage
                                                            .BatchEntry(CoroutinePuzzleBatchEntry(callId, payload)),
                                                    )
                                                } catch (t: Throwable) {
                                                    exception?.addSuppressed(t) ?: throw t
                                                }
                                                exception?.let { throw it }
                                            }
                                        }

                                        override suspend fun <T> expectingMatchedParallelism(block: suspend CoroutineScope.() -> T): T =
                                            withContext(InStrictParallelismExpectation) { block() }
                                    }
                                ) {
                                    coroutineScope { builder() }
                                }
                            } catch (t: Throwable) {
                                exceptionFromExpectation = t
                            } finally {
                                try {
                                    coroutinePuzzleSubmissionFunction.batched(null /* null is completion signal */)
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
                )
            }.also { outgoing.send(CoroutinePuzzleExpectationBatchOrCompletion.Completion(it)) }
        }
    } catch (c: CoroutinePuzzleFailedControlFlowException) {
        outgoing.send(CoroutinePuzzleExpectationBatchOrCompletion.Completion(c.result))
    } finally {
        outgoing.close()
    }
}

private suspend fun puzzleStateActor(
    events: ReceiveChannel<InternalPuzzleEvent>,
    emitBatch: suspend (CoroutinePuzzleBatch<ExpectationPayload>) -> Unit,
    runningTasks: ConcurrentHashMap<Long, Job>,
): CoroutinePuzzleSolutionResult {
    val accumulatedExpectations = mutableListOf<CoroutinePuzzleEndPointWaitingState>()
    val accumulatedSubmissions = mutableListOf<CoroutinePuzzleBatchEntry<SubmissionPayload.CallSubmitted>>()
    val (firstExpectationAndResults, firstSubmission) = events.receiveFirstTwoEvents()
    var submissionsAndCancellations: List<CoroutinePuzzleBatchEntry<SubmissionPayload>>? = firstSubmission
    var expectationAndResults: List<SuspendedBatchCall<InternalCoroutineExpectationMessage?, Pair<JsonElement, Long>?>> = firstExpectationAndResults

    while (true) {
        val expectationsAreDone = expectationAndResults.singleOrNull().let { it != null && it.query == null }
        when {
            expectationsAreDone && submissionsAndCancellations == null -> return CoroutinePuzzleSolutionResult.Success
            expectationsAreDone -> return MoreSubmissionsThanExpectationsFailure(
                overshotSubmissions = submissionsAndCancellations!!.map { (it.payload as SubmissionPayload.CallSubmitted).endPoint },
            )
            submissionsAndCancellations == null -> return MoreExpectationsThanSubmissionsFailure(
                expectedFollowups = expectationAndResults.map { it.query }
                    .filterIsInstance<InternalCoroutineExpectationMessage.Expectation>()
                    .map { it.state.endPoint }
                    .plus(accumulatedExpectations.map { it.query.state.endPoint }),
            )
        }

        if (expectationAndResults.isEmpty() && submissionsAndCancellations.isEmpty()) {
            return CoroutinePuzzleSolutionResult.FullyQuiescent
        }

        val (newExpectations, results) = expectationAndResults.partitionExpectationsAndResults()
        expectationAndResults = expectationAndResults.filter { it.query !is InternalCoroutineExpectationMessage.Expectation }
        val (newSubmissions, cancellations) = submissionsAndCancellations.partitionSubmissionsAndCancellations()
        submissionsAndCancellations = submissionsAndCancellations.filter { it.payload !is SubmissionPayload.CallSubmitted }
        accumulatedExpectations.addAll(newExpectations)
        accumulatedSubmissions.addAll(newSubmissions)
        assert(accumulatedExpectations.map { it.query.state.isStrictParallelism }.toSet().size < 2)

        if (results.isEmpty()) {
            strictParallelismValidation(accumulatedExpectations, accumulatedSubmissions)

            val matches = accumulatedSubmissions.mapNotNull { submission ->
                accumulatedExpectations
                    .lastOrNull { it.query.state.endPoint == submission.payload.endPoint }
                    // Make sure we don't process the same expectation twice
                    ?.also { accumulatedExpectations.remove(it) }
                    ?.to(submission)
            }.onEach { accumulatedSubmissions.remove(it.second) }

            if (matches.isEmpty() && cancellations.isEmpty()) {
                failInternal(CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
                    unexpectedSubmissions = accumulatedSubmissions.map { it.payload.endPoint },
                    expectations = accumulatedExpectations.map { it.query.state.endPoint },
                ))
            }

            matches.firstOrNull()?.first?.continuation?.runOnScopeThatTracksQuiescence {
                cancellations.forEach { runningTasks[it.callId]!!.cancel(CancellationAcrossRpc()) }

                matches.forEach { (expectation, submission) ->
                    expectation.continuation.resume(submission.payload.arg to submission.callId)
                }
            }   // Hmm sadly technically the following still has the race condition that runOnScopeThatTracksQuiescence tries to solve :(
                ?: cancellations.forEach { runningTasks[it.callId]!!.cancel(CancellationAcrossRpc()) }
            submissionsAndCancellations = submissionsAndCancellations.filter { it.payload !is SubmissionPayload.CallShouldCancel }

            expectationAndResults = (events.receive() as InternalPuzzleEvent.ExpectationBatch).expectations
        } else {
            results.resumeAllQuiescentTrackedScope { it.continuation.resume(null) }
            expectationAndResults = (events.receive() as InternalPuzzleEvent.ExpectationBatch).expectations

            emitBatch(results.map { it.query.reply.also { reply -> runningTasks.remove(reply.callId) } })

            submissionsAndCancellations = (events.receive() as InternalPuzzleEvent.SubmissionBatch).submissions
        }
    }
}

private fun strictParallelismValidation(
    accumulatedExpectations: MutableList<CoroutinePuzzleEndPointWaitingState>,
    submissions: List<CoroutinePuzzleBatchEntry<SubmissionPayload.CallSubmitted>>,
) {
    if (accumulatedExpectations.isEmpty()) return
    val expectedDescriptors = accumulatedExpectations.groupingBy { it.query.state.endPoint }.eachCount()
    if (
        accumulatedExpectations.first().query.state.isStrictParallelism &&
        expectedDescriptors != submissions.map { it.payload.endPoint }.groupingBy { it }.eachCount()
    ) {
        failInternal(CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure(
            submissions.map { it.payload.endPoint },
            expectedDescriptors.keys.toList(),
        ))
    }
}

private fun List<CoroutinePuzzleBatchEntry<SubmissionPayload>>.partitionSubmissionsAndCancellations(): Pair<
    List<CoroutinePuzzleBatchEntry<SubmissionPayload.CallSubmitted>>,
    List<CoroutinePuzzleBatchEntry<SubmissionPayload.CallShouldCancel>>,
> {
    @Suppress("UNCHECKED_CAST")
    return this.partition { it.payload is SubmissionPayload.CallSubmitted } as Pair<
        List<CoroutinePuzzleBatchEntry<SubmissionPayload.CallSubmitted>>,
        List<CoroutinePuzzleBatchEntry<SubmissionPayload.CallShouldCancel>>,
    >
}

private data class CoroutinePuzzleEndPointExpectation(
    val endPoint: CoroutinePuzzleEndPointDescriptor,
    val isStrictParallelism: Boolean,
)

private typealias CoroutinePuzzleEndPointWaitingState = SuspendedBatchCall<InternalCoroutineExpectationMessage.Expectation, Pair<JsonElement, Long>>

private fun List<SuspendedBatchCall<InternalCoroutineExpectationMessage?, Pair<JsonElement, Long>?>>.partitionExpectationsAndResults(
): Pair<
    List<SuspendedBatchCall<InternalCoroutineExpectationMessage.Expectation, Pair<JsonElement, Long>>>,
    List<SuspendedBatchCall<InternalCoroutineExpectationMessage.BatchEntry, Nothing?>>,
> {
    @Suppress("UNCHECKED_CAST")
    return this.partition { it.query is InternalCoroutineExpectationMessage.Expectation } as Pair<
        List<SuspendedBatchCall<InternalCoroutineExpectationMessage.Expectation, Pair<JsonElement, Long>>>,
        List<SuspendedBatchCall<InternalCoroutineExpectationMessage.BatchEntry, Nothing?>>,
    >
}

private suspend fun ReceiveChannel<InternalPuzzleEvent>.receiveFirstTwoEvents(
): Pair<List<SuspendedBatchCall<InternalCoroutineExpectationMessage?, Pair<JsonElement, Long>?>>, List<CoroutinePuzzleBatchEntry<SubmissionPayload>>?> {
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
    data class Expectation(val state: CoroutinePuzzleEndPointExpectation): InternalCoroutineExpectationMessage()
    data class BatchEntry(val reply: CoroutinePuzzleBatchEntry<ExpectationPayload>): InternalCoroutineExpectationMessage()
}

private sealed class InternalPuzzleEvent {
    data class SubmissionBatch(
        /** Null means submissions are done */
        val submissions: List<CoroutinePuzzleBatchEntry<SubmissionPayload>>?,
    ): InternalPuzzleEvent()
    data class ExpectationBatch(
        /** Null query means submissions are done */
        val expectations: List<SuspendedBatchCall<InternalCoroutineExpectationMessage?, Pair<JsonElement, Long>?>>,
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
fun fail(message: String): Nothing = fail(CoroutinePuzzleSolutionResult.CustomFailure(message))

context(builder: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint</* @Exact */T, /* @Exact */R>.expectCall(
    noinline valueProducer: suspend (T) -> R,
): T = builder.expectCallTo(this, serializer(), serializer(), valueProducer)

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
suspend fun <T> expectingMatchedParallelism(block: suspend CoroutineScope.() -> T): T =
    builder.expectingMatchedParallelism(block)

context(builder: CoroutinePuzzleBuilderScope)
inline fun verify(condition: Boolean, message: () -> String) {
    if (!condition) fail(CoroutinePuzzleSolutionResult.CustomFailure(message()))
}
context(builder: CoroutinePuzzleBuilderScope)
inline fun <T : Any> T?.verifyNotNull(message: () -> String): T =
    this ?: fail(CoroutinePuzzleSolutionResult.CustomFailure(message()))

context(builder: CoroutinePuzzleBuilderScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint</* @Exact */ R, /* @Exact */ T>.expectCall(
    value: T,
): R = expectCall { value }
