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
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import java.security.MessageDigest
import kotlin.properties.PropertyDelegateProvider

@ConsistentCopyVisibility
data class CoroutinePuzzleEndPoint<T, R> internal constructor(val id: CoroutinePuzzleEndPointId)

/** A waiting endpoint call, including a higher-order call currently waiting for its next callback invocation. */
data class UnmatchedSubmission<T>(
    val endPoint: CoroutinePuzzleEndPoint<T, *>,
    val argument: T,
)

/** A stable, shared declaration of the endpoints used by a puzzle API. */
open class EndpointDescriptorRegistry {
    private val declarations = linkedMapOf<CoroutinePuzzleEndPointId, CommonEndpointMetadata<*, *>>()
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
                    declaration.callbackTypes.forEach {
                        it.updateDigest(digest)
                        digest.update(0.toByte())
                    }
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
        callbackTypes: List<SerialDescriptor> = emptyList(),
    ): CoroutinePuzzleEndPoint<T, R> = synchronized(declarations) {
        check(endpointHash == null) { "EndpointDescriptorCollection has already been sealed." }
        val descriptor = CoroutinePuzzleEndPointId(id)
        CoroutinePuzzleEndPoint<T, R>(descriptor).also { endpoint ->
            check(declarations.put(
                descriptor,
                CommonEndpointMetadata(endpoint, argumentSerializer, resultSerializer, description, callbackTypes),
            ) == null) { "Duplicate entries for $id" }
        }
    }

    fun endpointFor(descriptor: CoroutinePuzzleEndPointId): CoroutinePuzzleEndPoint<*, *> =
        getMetadata(descriptor).endpoint

    fun descriptionFor(descriptor: CoroutinePuzzleEndPointId): String = getMetadata(descriptor).description

    fun unmatchedSubmission(descriptor: CoroutinePuzzleEndPointId, argument: JsonElement): UnmatchedSubmission<*> =
        getMetadata(descriptor).unmatchedSubmission(argument)

    private fun getMetadata(descriptor: CoroutinePuzzleEndPointId): CommonEndpointMetadata<*, *> {
        check(endpointHash != null) { "EndpointDescriptorCollection has not yet been sealed" }
        return declarations[descriptor] ?: throw MetadataNotFoundException(descriptor)
    }

    fun endpointHash(): String = endpointHash ?: error("EndpointDescriptorCollection has not been sealed yet.")
}

private data class CommonEndpointMetadata<T, R>(
    val endpoint: CoroutinePuzzleEndPoint<T, R>,
    val argumentType: KSerializer<T>,
    val resultType: KSerializer<R>,
    val description: String,
    val callbackTypes: List<SerialDescriptor> = emptyList(),
) {
    fun unmatchedSubmission(argument: JsonElement): UnmatchedSubmission<T> =
        UnmatchedSubmission(endpoint, Json.decodeFromJsonElement(argumentType, argument))
}

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
): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, CoroutinePuzzleEndPoint<Pair<T, (R) -> Unit>, Unit>>> =
    higherOrderDescriptor<T, R, Unit, Unit>(flowFunction)

inline fun <reified A, reified T, reified U, reified R> EndpointDescriptorRegistry.higherOrderDescriptor(
    description: String,
): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, CoroutinePuzzleEndPoint<Pair<A, (T) -> U>, R>>> =
    endpointDelegate { name ->
        endpoint(name, description, PairSerializer(serializer<A>(), CallbackReferenceSerializer()), serializer<R>(),
            callbackTypes = listOf(serializer<T>().descriptor, serializer<U>().descriptor))
    }

/** The callback slot in an endpoint's argument snapshot; invocation goes through the suspend expectation API. */
data class CoroutinePuzzleCallbackReference<T, U>(val callId: Long) : (T) -> U {
    override fun invoke(value: T): U = error("Invoke callbacks through the suspend callback supplied to expectCall")
}

@PublishedApi
internal class CallbackReferenceSerializer<T, U> : KSerializer<(T) -> U> {
    override val descriptor = PrimitiveSerialDescriptor("kmpworkshop.CallbackReference", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: (T) -> U) {
        val reference = value as? CoroutinePuzzleCallbackReference<T, U>
            ?: error("Submit callbacks with submitCall(argument, callback); only callback references can be serialized")
        encoder.encodeLong(reference.callId)
    }
    override fun deserialize(decoder: Decoder): (T) -> U = CoroutinePuzzleCallbackReference(decoder.decodeLong())
}

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
    suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(
        topic: JsonElement,
        function: suspend CoroutineScope.(JsonElement) -> JsonElement,
    ): JsonElement
}

typealias CoroutinePuzzleBatch<T> = List<WithCallId<T>>

@Serializable data class WithCallId<out T>(val callId: Long, val payload: T)

/**
 * The same call lifecycle in both directions: request cancellation, or report how this side finished.
 * Callback invocations have their own call IDs and use the same cancellation and outcomes.
 */
@Serializable sealed class CoroutinePuzzleCallPayload<out T> {
    @Serializable sealed class Submission : CoroutinePuzzleCallPayload<Nothing>() {
        abstract val endPoint: CoroutinePuzzleEndPointId
        abstract val arg: JsonElement
    }
    /** Starts a call. Only the submitting side sends this message. */
    @Serializable data class CallSubmitted(
        override val endPoint: CoroutinePuzzleEndPointId,
        override val arg: JsonElement,
    ) : Submission()
    @Serializable data class HigherOrderCallSubmitted(
        override val endPoint: CoroutinePuzzleEndPointId,
        override val arg: JsonElement,
    ) : Submission()
    @Serializable data class CallbackInvoked(val parentCallId: Long, val arg: JsonElement) : CoroutinePuzzleCallPayload<Nothing>()
    /** Requests peer cancellation; a peer that already finished can replay its existing outcome. */
    @Serializable data object Cancel : CoroutinePuzzleCallPayload<Nothing>()

    /** Terminal for the sender's work, including its structured children and cancellation cleanup. */
    @Serializable sealed class Outcome<out T> : CoroutinePuzzleCallPayload<T>() {
        @Serializable data class Returned<T>(val result: T) : Outcome<T>()
        @Serializable data class Threw(val message: String, val originCallId: Long? = null) : Outcome<Nothing>()
        @Serializable data object Cancelled : Outcome<Nothing>()
    }
}

typealias CoroutinePuzzleSubmissionPayload = CoroutinePuzzleCallPayload<JsonElement>
typealias CoroutinePuzzleExpectationPayload = CoroutinePuzzleCallPayload<JsonElement>

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
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint<T, R>.submitCall(t: T): R = with(solutionScope) {
    Json.decodeFromJsonElement<R>(submitRawCall(Json.encodeToJsonElement(t)))
}

/** Submits an argument and a callback which the expectation can invoke zero or more times. */
context(solutionScope: CoroutinePuzzleSolutionScope)
suspend inline fun <reified A, reified T, reified U, reified R> CoroutinePuzzleEndPoint<Pair<A, (T) -> U>, R>.submitCall(
    argument: A,
    noinline callback: suspend CoroutineScope.(T) -> U,
): R = with(solutionScope) {
    Json.decodeFromJsonElement<R>(submitRawCall(Json.encodeToJsonElement(argument)) { value ->
        Json.encodeToJsonElement(callback(Json.decodeFromJsonElement<T>(value)))
    })
}

context(_: CoroutinePuzzleSolutionScope)
suspend inline fun <reified T, reified U, reified R> CoroutinePuzzleEndPoint<Pair<Unit, (T) -> U>, R>.submitCall(
    noinline callback: suspend CoroutineScope.(T) -> U,
): R = submitCall(Unit, callback)

class CoroutinePuzzleResultWithHistory(
    val result: CoroutinePuzzleSolutionResult,
    val history: List<CoroutinePuzzleHistoryBatch>,
)

/** A call the evaluator was still waiting for when the solution attempt ended. */
@Serializable data class CoroutinePuzzleExpectedFollowup(
    val endPoint: CoroutinePuzzleEndPointId,
    /** Allows an expectation to optionally expose what the expected argument is. */
    val expectedArgument: JsonElement? = null,
    /** The already matched the call that the evaluator expected to be cancelled. */
    val expectedCancellationOfCallId: Long? = null,
    /** A callback invocation whose structured work has not finished. */
    val expectedCallbackCompletionOfCallId: Long? = null,
    /** The call the evaluator cancelled, whose submission-side cleanup has not finished yet. */
    val expectedCancellationCompletionOfCallId: Long? = null,
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
