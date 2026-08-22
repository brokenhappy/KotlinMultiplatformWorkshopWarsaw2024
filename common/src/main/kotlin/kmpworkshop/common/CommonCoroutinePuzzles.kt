package kmpworkshop.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.properties.ReadOnlyProperty
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import java.security.MessageDigest
import kotlin.properties.PropertyDelegateProvider

@ConsistentCopyVisibility
data class CoroutinePuzzleEndPoint<T, R> internal constructor(val id: CoroutinePuzzleEndPointId)

/** A stable, shared declaration of the endpoints used by a puzzle API. */
open class EndpointDescriptorRegistry {
    private val declarations = linkedMapOf<CoroutinePuzzleEndPointId, CommonEndpointMetadata>()
    private var endpointHash: String? = null

    protected fun seal() {
        synchronized(declarations) {
            check(endpointHash == null) { "EndpointDescriptorCollection has already been sealed." }
            val digest = MessageDigest.getInstance("SHA-256")
            declarations
                .values
                .sortedBy { it.endpoint.id.stringValue }
                .forEach { declaration ->
                    digest.update(declaration.endpoint.id.stringValue.encodeToByteArray())
                    digest.update(0.toByte())
                    declaration.argumentType.descriptor.updateDigest(digest)
                    digest.update(0.toByte())
                    declaration.resultType.descriptor.updateDigest(digest)
                    digest.update(0.toByte())
                }
            endpointHash = digest.digest().toHexString()
        }
    }

    @PublishedApi
    internal fun <T, R> endpoint(
        id: String,
        description: String,
        argumentSerializer: KSerializer<T>,
        resultSerializer: KSerializer<R>,
    ): CoroutinePuzzleEndPoint<T, R> = synchronized(declarations) {
        check(endpointHash == null) { "EndpointDescriptorCollection has already been sealed." }
        val descriptor = CoroutinePuzzleEndPointId(id)
        CoroutinePuzzleEndPoint<T, R>(descriptor).also { endpoint ->
            check(declarations.put(
                descriptor,
                CommonEndpointMetadata(endpoint, argumentSerializer, resultSerializer, description),
            ) == null) { "Duplicate entries for $id" }
        }
    }

    fun endpointFor(descriptor: CoroutinePuzzleEndPointId): CoroutinePuzzleEndPoint<*, *> {
        check(endpointHash != null) { "EndpointDescriptorCollection has not yet been sealed" }
        return declarations[descriptor]?.endpoint ?: throw MetadataNotFoundException(descriptor)
    }

    fun descriptionFor(descriptor: CoroutinePuzzleEndPointId): String =
        declarations[descriptor]?.description ?: throw MetadataNotFoundException(descriptor)

    fun endpointHash(): String = endpointHash ?: error("EndpointDescriptorCollection has not been sealed yet.")
}

private data class CommonEndpointMetadata(
    val endpoint: CoroutinePuzzleEndPoint<*, *>,
    val argumentType: KSerializer<*>,
    val resultType: KSerializer<*>,
    val description: String,
)

private fun SerialDescriptor.updateDigest(digest: MessageDigest) {
    digest.update(serialName.encodeToByteArray())
    if (elementsCount != 0) {
        digest.update('<'.code.toByte())
        for (index in 0 until elementsCount) {
            if (index != 0) digest.update(','.code.toByte())
            getElementDescriptor(index).updateDigest(digest)
        }
        digest.update('>'.code.toByte())
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }

inline fun <reified T, reified R> EndpointDescriptorRegistry.descriptor(
    description: String,
): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, CoroutinePuzzleEndPoint<T, R>>> =
    endpointDelegate { name -> endpoint(name, description, serializer(), serializer()) }

inline fun <reified T, reified R> EndpointDescriptorRegistry.flowDescriptor(
    flowFunction: String,
): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, CoroutinePuzzleEndPoint<WithCallId<T>, WithCallId<ValueOrCompletion<R>>>>> =
    endpointDelegate { name -> endpoint(name, flowFunction, serializer(), serializer()) }

@PublishedApi
internal fun <T> endpointDelegate(factory: (String) -> T): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, T>> =
    PropertyDelegateProvider { _, property ->
        val value = factory(property.name)
        ReadOnlyProperty { _, _ -> value }
    }

@Serializable
@JvmInline
value class CoroutinePuzzleEndPointId(val stringValue: String)

interface CoroutinePuzzleSolutionScope {
    suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement
}

typealias CoroutinePuzzleBatch<T> = List<WithCallId<T>>

@Serializable data class WithCallId<T>(val callId: Long, val payload: T)

@Serializable sealed class CoroutinePuzzleSubmissionPayload {
    @Serializable data class CallSubmitted(
        val endPoint: CoroutinePuzzleEndPointId,
        val arg: JsonElement,
    ) : CoroutinePuzzleSubmissionPayload()
    @Serializable data object CallShouldCancel : CoroutinePuzzleSubmissionPayload()
}

@Serializable sealed class CoroutinePuzzleExpectationPayload {
    @Serializable data class CallAnswered(val result: JsonElement) : CoroutinePuzzleExpectationPayload()
    @Serializable data class CallThrew(val message: String) : CoroutinePuzzleExpectationPayload()
    @Serializable data object CallCancellationCompleted : CoroutinePuzzleExpectationPayload()
}

/** A value sent to one Flow collector, or the end of that collector's stream. */
@Serializable sealed class ValueOrCompletion<out T> {
    @Serializable data class Value<T>(val value: T) : ValueOrCompletion<T>()
    @Serializable data object Completion : ValueOrCompletion<Nothing>()
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
    with(solutionScope) { this@submitRawCall.submitRawCall(t) }

class CoroutinePuzzleResultWithHistory(
    val result: CoroutinePuzzleSolutionResult,
    val history: List<CoroutinePuzzleHistoryBatch>,
)

/** A call the evaluator was still waiting for when the solution attempt ended. */
@Serializable data class CoroutinePuzzleExpectedFollowup(
    val endPoint: CoroutinePuzzleEndPointId,
    /** Optional argument metadata for correlating this expectation with history; it does not affect matching. */
    val expectedArgument: JsonElement? = null,
    /** The already matched call that the evaluator expected to be cancelled. */
    val expectedCancellationOfCallId: Long? = null,
)

@Serializable sealed class CoroutinePuzzleSolutionResult {
    @Serializable data object Success : CoroutinePuzzleSolutionResult()
    @Serializable data class MoreSubmissionsThanExpectationsFailure(
        val overshotSubmissions: List<CoroutinePuzzleEndPointId>,
    ) : CoroutinePuzzleSolutionResult()
    @Serializable data class MoreExpectationsThanSubmissionsFailure(
        val expectedFollowups: List<CoroutinePuzzleExpectedFollowup>,
    ) : CoroutinePuzzleSolutionResult()
    @Serializable data class ExactParallelismMismatchFailure(
        val submissions: List<CoroutinePuzzleEndPointId>,
        val expectations: List<CoroutinePuzzleExpectedFollowup>,
        /** Submissions an explicit concurrent check conclusively identified as incorrect. */
        val incorrectSubmissions: List<CoroutinePuzzleEndPointId>,
    ) : CoroutinePuzzleSolutionResult()
    @Serializable data class UnexpectedSubmissionsFailure(
        val unexpectedSubmissions: List<CoroutinePuzzleEndPointId>,
        val expectations: List<CoroutinePuzzleExpectedFollowup>,
    ) : CoroutinePuzzleSolutionResult()
    @Serializable data object FullyQuiescent : CoroutinePuzzleSolutionResult()
    @Serializable data class CustomFailure(
        val message: String,
        /** Submissions a puzzle-specific check conclusively identified as incorrect. */
        val incorrectSubmissions: List<CoroutinePuzzleEndPointId> = emptyList(),
    ) : CoroutinePuzzleSolutionResult()
}
