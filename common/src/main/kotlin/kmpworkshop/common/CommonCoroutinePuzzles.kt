package kmpworkshop.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.jvm.JvmInline

data class CoroutinePuzzleEndPoint<T, R>(val descriptor: CoroutinePuzzleEndPointDescriptor)

@Serializable
@JvmInline
value class CoroutinePuzzleEndPointDescriptor(
    /**
     * This property has 2 functions.
     *  - It's visible to the user. Reading it makes it clear to the user what function call caused this or they should make.
     *  - It must be unique per puzzle. Expectations use this as a key to map to the submissions.
     */
    val description: String,
)

fun <T, R> coroutinePuzzleEndPoint(description: String): CoroutinePuzzleEndPoint<T, R> =
    CoroutinePuzzleEndPoint(CoroutinePuzzleEndPointDescriptor(description))

interface CoroutinePuzzleSolutionScope {
    suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement
}

typealias CoroutinePuzzleBatch<T> = List<CoroutinePuzzleBatchEntry<T>>

@Serializable data class CoroutinePuzzleBatchEntry<T>(val callId: Long, val payload: T) {
    @Serializable sealed class SubmissionPayload {
        @Serializable data class CallSubmitted(
            val endPoint: CoroutinePuzzleEndPointDescriptor,
            val arg: JsonElement,
        ) : SubmissionPayload()
        @Serializable data object CallShouldCancel : SubmissionPayload()
    }
    @Serializable sealed class ExpectationPayload {
        @Serializable data class CallAnswered(val result: JsonElement) : ExpectationPayload()
        @Serializable data class CallThrew(val message: String) : ExpectationPayload()
        @Serializable data object CallCancellationCompleted : ExpectationPayload()
    }
}

fun interface CoroutinePuzzle {
    fun solveAsFlow(solution: CoroutinePuzzleSolution): Flow<CoroutinePuzzleSolveState>
}

typealias CoroutinePuzzleSolution = suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit

suspend fun CoroutinePuzzle.solve(solution: CoroutinePuzzleSolution): CoroutinePuzzleResultWithHistory =
    solveAsFlow(solution).toResultWithHistory()

suspend fun Flow<CoroutinePuzzleSolveState>.toResultWithHistory(): CoroutinePuzzleResultWithHistory {
    val history = mutableListOf<CoroutinePuzzleHistoryBatch>()
    var result: CoroutinePuzzleSolutionResult? = null
    collect { state ->
        when (state) {
            is CoroutinePuzzleSolveState.Running -> history += state.batch
            is CoroutinePuzzleSolveState.Completed -> result = state.result
        }
    }
    return CoroutinePuzzleResultWithHistory(result!!, history.toList())
}

sealed class CoroutinePuzzleSolveState {
    data class Running(val batch: CoroutinePuzzleHistoryBatch) : CoroutinePuzzleSolveState()
    data class Completed(val result: CoroutinePuzzleSolutionResult) : CoroutinePuzzleSolveState()
}

context(solutionScope: CoroutinePuzzleSolutionScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint<T, R>.submitCall(t: T): R =
    Json.decodeFromJsonElement<R>(submitRawCall(Json.encodeToJsonElement<T>(t)))

context(solutionScope: CoroutinePuzzleSolutionScope)
suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement =
    with(solutionScope) { submitRawCall(t) }

class CoroutinePuzzleResultWithHistory(
    val result: CoroutinePuzzleSolutionResult,
    val history: List<CoroutinePuzzleHistoryBatch>,
)

@Serializable sealed class CoroutinePuzzleSolutionResult {
    @Serializable data object Success : CoroutinePuzzleSolutionResult()
    @Serializable data class MoreSubmissionsThanExpectationsFailure(
        val overshotSubmissions: List<CoroutinePuzzleEndPointDescriptor>,
    ) : CoroutinePuzzleSolutionResult()
    @Serializable data class MoreExpectationsThanSubmissionsFailure(
        val expectedFollowups: List<CoroutinePuzzleEndPointDescriptor>,
    ) : CoroutinePuzzleSolutionResult()
    @Serializable data class ExactParallelismMismatchFailure(
        val submissions: List<CoroutinePuzzleEndPointDescriptor>,
        val expectations: List<CoroutinePuzzleEndPointDescriptor>,
    ) : CoroutinePuzzleSolutionResult()
    @Serializable data class UnexpectedSubmissionsFailure(
        val unexpectedSubmissions: List<CoroutinePuzzleEndPointDescriptor>,
        val expectations: List<CoroutinePuzzleEndPointDescriptor>,
    ) : CoroutinePuzzleSolutionResult()
    @Serializable data object FullyQuiescent : CoroutinePuzzleSolutionResult()
    @Serializable data class CustomFailure(val message: String) : CoroutinePuzzleSolutionResult()
}
