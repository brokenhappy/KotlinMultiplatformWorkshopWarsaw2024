@file:OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)

package kmpworkshop.common

import kmpworkshop.common.CoroutinePuzzleBatchEntry.ExpectationPayload
import kmpworkshop.common.CoroutinePuzzleBatchEntry.SubmissionPayload
import kmpworkshop.common.CoroutinePuzzleBatchEntry.SubmissionPayload.CallSubmitted
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime

data class CoroutinePuzzleProtocol(
    val expectations: ReceiveChannel<CoroutinePuzzleExpectationBatchOrCompletion>,
    val submissions: SendChannel<CoroutinePuzzleBatch<SubmissionPayload>>,
)

sealed class CoroutinePuzzleHistoryBatch {
    data class Submission(val entries: List<CoroutinePuzzleBatchEntry<SubmissionPayload>>) : CoroutinePuzzleHistoryBatch()
    data class Expectation(val entries: List<CoroutinePuzzleBatchEntry<ExpectationPayload>>) : CoroutinePuzzleHistoryBatch()
}

suspend fun CoroutinePuzzle.solve(
    solution: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit
): CoroutinePuzzleResultWithHistory = this@solve.use { (expectations, submissions) ->
    val pending = ConcurrentHashMap<Long, GuardedContinuation<JsonElement>>()
    val history = mutableListOf<CoroutinePuzzleHistoryBatch>()
    val submissionFunction = AutoBatchedFunctionId<CoroutinePuzzleBatchEntry<SubmissionPayload>, JsonElement>(
        fallbackOutOfBatchScope = {
            val addition =
                (it.payload as? CallSubmitted)?.let { " when you tried to call ${it.endPoint}" } ?: ""
            error("""
                You broke structured concurrency$addition.

                This is most likely because you used GlobalScope. Or created your own CoroutineScope.
                If you're stuck, feel free to ask the workshop host :).
            """.trimIndent())
        },
        batchResumer = { batch ->
            history.add(CoroutinePuzzleHistoryBatch.Submission(batch.map { it.query }))
            batch.forEach { pending[it.query.callId] = it.continuation }
            submissions.send(batch.map { it.query })
        }
    )


    coroutineScope {
        withLaunched(taskThatMustOutliveUsage = {
            try {
                submissionFunction.autoBatchedOnQuiescence {
                    withImportantCleanup {
                        context(submissionFunction.asSolutionScope()) {
                            solution()
                        }
                    }
                }
            } finally {
                submissions.close()
            }
        }) {
            messageReceivingActor(expectations, pending, onBatchReceived = {
                history.add(CoroutinePuzzleHistoryBatch.Expectation(it))
            }).withHistory(history)
        }
    }
}

class ExceptionAcrossRpc: Exception(null, null, false, false)
class CancellationAcrossRpc: CancellationException(null)

private fun CoroutinePuzzleSolutionResult.withHistory(history: List<CoroutinePuzzleHistoryBatch>): CoroutinePuzzleResultWithHistory =
    CoroutinePuzzleResultWithHistory(this, history)

private fun AutoBatchedFunctionId<CoroutinePuzzleBatchEntry<SubmissionPayload>, JsonElement>.asSolutionScope(): CoroutinePuzzleSolutionScope =
    object : CoroutinePuzzleSolutionScope {
        private val callIdCounter = AtomicLong(0)
        override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement {
            val callId = callIdCounter.incrementAndFetch()
            return try {
                batched(CoroutinePuzzleBatchEntry(callId, CallSubmitted(descriptor, t)))
            } catch (c: CancellationException) {
                if (!currentCoroutineContext().isActive) {
                    importantCleanup {
                        batched(CoroutinePuzzleBatchEntry(callId, SubmissionPayload.CallShouldCancel))
                    }
                }
                throw c
            }
        }
    }

private suspend fun messageReceivingActor(
    batchOrCompletions: ReceiveChannel<CoroutinePuzzleExpectationBatchOrCompletion>,
    pending: ConcurrentHashMap<Long, GuardedContinuation<JsonElement>>,
    onBatchReceived: (List<CoroutinePuzzleBatchEntry<ExpectationPayload>>) -> Unit,
): CoroutinePuzzleSolutionResult {
    for (batchOrCompletion in batchOrCompletions) {
        when (batchOrCompletion) {
            is CoroutinePuzzleExpectationBatchOrCompletion.Batch -> {
                onBatchReceived(batchOrCompletion.batch)
                batchOrCompletion.batch.mapNotNull { entry ->
                    pending.remove(entry.callId)?.to(entry.payload.toResult())
                }.resumeAllQuiescentTrackedScope(mapper = { it.first }) { (continuation, answer) ->
                    continuation.resumeWith(answer)
                }
            }
            is CoroutinePuzzleExpectationBatchOrCompletion.Completion -> return batchOrCompletion.result
        }
    }
    return CoroutinePuzzleSolutionResult.Success
}

private fun ExpectationPayload.toResult(): Result<JsonElement> = when (this) {
    ExpectationPayload.CallCancellationCompleted -> Result.failure(CancellationAcrossRpc())
    is ExpectationPayload.CallAnswered -> Result.success(result)
    ExpectationPayload.CallThrew -> Result.failure(ExceptionAcrossRpc())
}

@Serializable sealed class CoroutinePuzzleExpectationBatchOrCompletion {
    @Serializable data class Batch(
        val batch: CoroutinePuzzleBatch<ExpectationPayload>,
    ) : CoroutinePuzzleExpectationBatchOrCompletion()
    @Serializable data class Completion(
        val result: CoroutinePuzzleSolutionResult,
    ) : CoroutinePuzzleExpectationBatchOrCompletion()
}

fun coroutinePuzzleCommunicationChannel(
    underlyingComms: suspend CoroutineScope.(
        fromExpectation: SendChannel<CoroutinePuzzleExpectationBatchOrCompletion>,
        fromSubmission: ReceiveChannel<CoroutinePuzzleBatch<SubmissionPayload>>,
    ) -> Unit,
): Resource<CoroutinePuzzleProtocol> = resource { cc ->
    val expectations = Channel<CoroutinePuzzleExpectationBatchOrCompletion>(64)
    val submissions = Channel<CoroutinePuzzleBatch<SubmissionPayload>>(64)
    try {
        launch { underlyingComms(expectations, submissions) }
        cc(CoroutinePuzzleProtocol(expectations, submissions))
    } finally {
        submissions.close()
        expectations.close()
    }
}


