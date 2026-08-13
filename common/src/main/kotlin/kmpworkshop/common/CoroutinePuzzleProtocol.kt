package kmpworkshop.common

import kmpworkshop.common.CoroutinePuzzleSubmissionPayload.CallSubmitted
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException

typealias CoroutinePuzzleProtocol =
    CommunicationProtocol<CoroutinePuzzleExpectationBatchOrCompletion, CoroutinePuzzleBatch<CoroutinePuzzleSubmissionPayload>>

sealed class CoroutinePuzzleHistoryBatch {
    data class Submission(val entries: List<WithCallId<CoroutinePuzzleSubmissionPayload>>) : CoroutinePuzzleHistoryBatch()
    data class Expectation(val entries: List<WithCallId<CoroutinePuzzleExpectationPayload>>) : CoroutinePuzzleHistoryBatch()
}

suspend fun Resource<CoroutinePuzzleProtocol>.solve(
    solution: CoroutinePuzzleSolution
): CoroutinePuzzleResultWithHistory = asPuzzle().solve(solution)

fun Resource<CoroutinePuzzleProtocol>.asPuzzle(): CoroutinePuzzle = CoroutinePuzzle { solution ->
    channelFlow {
        this@asPuzzle.use { (expectations, submissions) ->
            val pending = ConcurrentHashMap<Long, GuardedContinuation<JsonElement>>()
            val submissionFunction = AutoBatchedFunctionId<WithCallId<CoroutinePuzzleSubmissionPayload>, JsonElement>(
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
                    send(CoroutinePuzzleSolveState.Running(CoroutinePuzzleHistoryBatch.Submission(batch.map { it.query })))
                    batch.forEach { pending[it.query.callId] = it.continuation }
                    submissions.send(batch.map { it.query })
                }
            )


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
                val result = messageReceivingActor(expectations, pending, onBatchReceived = {
                    send(CoroutinePuzzleSolveState.Running(CoroutinePuzzleHistoryBatch.Expectation(it)))
                })
                send(CoroutinePuzzleSolveState.Completed(result))
            }
        }
    }
}

class ExceptionAcrossRpc(message: String): Exception(message, null, false, false)
class CancellationAcrossRpc: CancellationException(null)

@OptIn(ExperimentalAtomicApi::class)
private fun AutoBatchedFunctionId<WithCallId<CoroutinePuzzleSubmissionPayload>, JsonElement>.asSolutionScope(): CoroutinePuzzleSolutionScope =
    object : CoroutinePuzzleSolutionScope {
        private val callIdCounter = AtomicLong(0)
        override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement {
            val callId = callIdCounter.incrementAndFetch()
            return try {
                batched(WithCallId(callId, CallSubmitted(id, t)))
            } catch (c: CancellationException) {
                if (!currentCoroutineContext().isActive) {
                    importantCleanup {
                        batched(WithCallId(callId, CoroutinePuzzleSubmissionPayload.CallShouldCancel))
                    }
                }
                throw c
            }
        }
    }

private suspend fun messageReceivingActor(
    batchOrCompletions: ReceiveChannel<CoroutinePuzzleExpectationBatchOrCompletion>,
    pending: ConcurrentHashMap<Long, GuardedContinuation<JsonElement>>,
    onBatchReceived: suspend (List<WithCallId<CoroutinePuzzleExpectationPayload>>) -> Unit,
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

private fun CoroutinePuzzleExpectationPayload.toResult(): Result<JsonElement> = when (this) {
    CoroutinePuzzleExpectationPayload.CallCancellationCompleted -> Result.failure(CancellationAcrossRpc())
    is CoroutinePuzzleExpectationPayload.CallAnswered -> Result.success(result)
    is CoroutinePuzzleExpectationPayload.CallThrew -> Result.failure(ExceptionAcrossRpc(message))
}

@Serializable sealed class CoroutinePuzzleExpectationBatchOrCompletion {
    @Serializable data class Batch(
        val batch: CoroutinePuzzleBatch<CoroutinePuzzleExpectationPayload>,
    ) : CoroutinePuzzleExpectationBatchOrCompletion()
    @Serializable data class Completion(
        val result: CoroutinePuzzleSolutionResult,
    ) : CoroutinePuzzleExpectationBatchOrCompletion()
}

fun coroutinePuzzleCommunicationChannel(
    underlyingComms: suspend CoroutineScope.(
        fromExpectation: SendChannel<CoroutinePuzzleExpectationBatchOrCompletion>,
        fromSubmission: ReceiveChannel<CoroutinePuzzleBatch<CoroutinePuzzleSubmissionPayload>>,
    ) -> Unit,
): Resource<CoroutinePuzzleProtocol> = communicationProtocol(underlyingComms)
