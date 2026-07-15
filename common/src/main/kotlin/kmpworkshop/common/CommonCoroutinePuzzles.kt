package kmpworkshop.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.jvm.JvmInline
import kotlin.time.ExperimentalTime

data class CoroutinePuzzleEndPoint<in T, out R>(val descriptor: CoroutinePuzzleEndPointDescriptor)

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

fun CoroutinePuzzleEndPointDescriptor.toEndpoint(): CoroutinePuzzleEndPoint<*, *> =
    CoroutinePuzzleEndPoint<Any?, Any?>(this)

fun <T, R> coroutinePuzzleEndPoint(description: String): CoroutinePuzzleEndPoint<T, R> =
    CoroutinePuzzleEndPoint(CoroutinePuzzleEndPointDescriptor(description))

sealed class CoroutinePuzzleState {
    class WaitingForExpectations(
        val expectedCallsLeftAfterLastResumption: List<CoroutinePuzzleEndPointWaitingState<*, *>>,
    ) : CoroutinePuzzleState()
    class WaitingForSubmissions(
        val expectedCalls: List<CoroutinePuzzleEndPointWaitingState<*, *>>,
        val isStrictParallelism: Boolean,
    ) : CoroutinePuzzleState()
    data object ExpectationsDone : CoroutinePuzzleState()
}

class CoroutinePuzzleEndPointWaitingState<T, R>(
    val endPoint: CoroutinePuzzleEndPoint<T, R>,
    val isStrictParallelism: Boolean,
    /** Is called from the submission side */
    val submitCall: suspend (JsonElement) -> JsonElement,
) {
    override fun toString(): String = """WaitingState(endPoint=${endPoint.descriptor.description})""".trimIndent()
}

data class CoroutinePuzzle(
    val puzzle: suspend CoroutineScope.(MutableStateFlow<CoroutinePuzzleState>) -> Unit,
)

interface CoroutinePuzzleSolutionScope {
    suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement
}

context(solutionScope: CoroutinePuzzleSolutionScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint<T, R>.submitCall(t: T): R =
    Json.decodeFromJsonElement<R>(submitRawCall(Json.encodeToJsonElement<T>(t)))

context(solutionScope: CoroutinePuzzleSolutionScope)
suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement =
    with(solutionScope) { submitRawCall(t) }

@Serializable sealed class CoroutinePuzzleSolutionResult {
    @Serializable data object Success : CoroutinePuzzleSolutionResult()
    @Serializable data class Failure(
        val history: List<CoroutinePuzzleEndPointDescriptor>,
        val reason: Reason,
    ) : CoroutinePuzzleSolutionResult() {
        @Serializable sealed class Reason {
            @Serializable data class MoreSubmissionsThanExpectations(
                val overshotSubmissions: List<CoroutinePuzzleEndPointDescriptor>,
            ) : Reason()
            @Serializable data class MoreExpectationsThanSubmissions(
                val expectedFollowups: List<CoroutinePuzzleEndPointDescriptor>,
            ) : Reason()
            @Serializable data class ExactParallelismMismatch(
                val submissions: List<CoroutinePuzzleEndPointDescriptor>,
                val expectations: List<CoroutinePuzzleEndPointDescriptor>,
            ) : Reason()
            @Serializable data class UnexpectedSubmissions(
                val unexpectedSubmissions: List<CoroutinePuzzleEndPointDescriptor>,
                val expectations: List<CoroutinePuzzleEndPointDescriptor>,
            ) : Reason()
            @Serializable data class Custom(val message: String) : Reason()
        }
    }
}

context(_: CoroutinePuzzleSolutionScope)
internal fun fail(reason: CoroutinePuzzleSolutionResult.Failure.Reason): Nothing =
    throw CoroutinePuzzleFailedControlFlowException(reason)

class CoroutinePuzzleFailedControlFlowException(
    val reason: CoroutinePuzzleSolutionResult.Failure.Reason,
) : Exception(null, null) // TODO: Optimize away stacktrace hydration?

suspend fun CoroutinePuzzle.solve(solution: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit): CoroutinePuzzleSolutionResult {
    val history = MutableStateFlow<List<CoroutinePuzzleEndPoint<*, *>>>(emptyList())
    return try {
        val puzzleState = MutableStateFlow<CoroutinePuzzleState>(CoroutinePuzzleState.WaitingForExpectations(emptyList()))
        val coroutinePuzzleSubmissionFunction = AutoBatchedFunctionId<SubmissionCall, JsonElement?>(
            batchResumer = { submissionCalls ->
                val submissions = submissionCalls.map { it.query }
                val expectations = puzzleState
                    .takeWhile { it !is CoroutinePuzzleState.ExpectationsDone }
                    .filterIsInstance<CoroutinePuzzleState.WaitingForSubmissions>()
                    .firstOrNull()
                    ?: throw CoroutinePuzzleFailedControlFlowException(
                        CoroutinePuzzleSolutionResult.Failure.Reason.MoreSubmissionsThanExpectations(
                            submissions.map { it.endPoint.descriptor },
                        )
                    )

                val expectedDescriptors = expectations.expectedCalls.map { it.endPoint.descriptor }.toSet()
                if (expectations.isStrictParallelism) {
                    if (
                        expectations.expectedCalls.size != submissions.size ||
                        expectedDescriptors != submissions.map { it.endPoint.descriptor }.toSet()
                    ) {
                        throw CoroutinePuzzleFailedControlFlowException(
                            CoroutinePuzzleSolutionResult.Failure.Reason.ExactParallelismMismatch(
                                submissions.map { it.endPoint.descriptor },
                                expectations.expectedCalls.map { it.endPoint.descriptor },
                            )
                        )
                    }
                } else {
                    val missingSubmissions = submissions.filter { it.endPoint.descriptor !in expectedDescriptors }
                    if (missingSubmissions.size == submissions.size) {
                        throw CoroutinePuzzleFailedControlFlowException(
                            CoroutinePuzzleSolutionResult.Failure.Reason.UnexpectedSubmissions(
                                unexpectedSubmissions = submissions.map { it.endPoint.descriptor },
                                expectations = expectations.expectedCalls.map { it.endPoint.descriptor },
                            )
                        )
                    }
                }

                // This makes sure that multiple expectations to the same endpoint are not processed multiple times.
                val processedExpectations = Collections.newSetFromMap<CoroutinePuzzleEndPointWaitingState<*, *>>(IdentityHashMap())
                coroutineScope {
                    val submissionsReadyToGo = submissionCalls.map { submissionCall ->
                        val matchingCall = expectations
                            .expectedCalls
                            .firstOrNull { it !in processedExpectations && it.endPoint.descriptor == submissionCall.query.endPoint.descriptor }
                            ?.also { processedExpectations.add(it) }

                        suspend { // We don't want to persist the side effect yet. We want to update the state first.
                            if (!submissionCall.continuation.isCancelled) {
                                launch {
                                    submissionCall.continuation.resumeWith(
                                        // The submissions that are not matched need to retry (when we return null)
                                        runCatching { matchingCall?.submitCall(submissionCall.query.argument) },
                                    )
                                }.also { job ->
                                    submissionCall.invokeOnCancellation {
                                        job.cancel()
                                    }
                                }
                            }
                        }
                    }
                    puzzleState.update { oldState ->
                        if (oldState !== expectations) throw IllegalStateException("Unexpected puzzle state $oldState")
                        CoroutinePuzzleState.WaitingForExpectations(expectations.expectedCalls.filter { it !in processedExpectations })
                    }
                    submissionsReadyToGo.forEach { it() }
                }
            }
        )
        coroutineScope {
            launch {
                puzzle(puzzleState)
            }
            context(
                object : CoroutinePuzzleSolutionScope {
                    override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement {
                        while (true) {
                            return coroutinePuzzleSubmissionFunction.batched(SubmissionCall(this, t)) ?: continue
                        }
                    }
                },
            ) {
                @OptIn(ExperimentalTime::class)
                coroutinePuzzleSubmissionFunction.autoBatchedOnQuiescence { solution(this) }
            }
            puzzleState
                .takeWhile { it !is CoroutinePuzzleState.ExpectationsDone }
                .filterIsInstance<CoroutinePuzzleState.WaitingForSubmissions>()
                .firstOrNull()
                ?.let {
                    throw CoroutinePuzzleFailedControlFlowException(
                        CoroutinePuzzleSolutionResult.Failure.Reason.MoreExpectationsThanSubmissions(
                            it.expectedCalls.map { it.endPoint.descriptor }
                        )
                    )
                } ?: CoroutinePuzzleSolutionResult.Success
        }
    } catch (e: CoroutinePuzzleFailedControlFlowException) {
        CoroutinePuzzleSolutionResult.Failure(history.value.map { it.descriptor }, e.reason)
    }
}

data class SubmissionCall(val endPoint: CoroutinePuzzleEndPoint<*, *>, val argument: JsonElement)

interface GetNumberAndSubmit {
    suspend fun getNumber(): Int
    suspend fun submit(sum: Int)
}

interface NumberFlowAndSubmit {
    fun numbers(): Flow<Int>
    suspend fun submit(number: Int)
}

context(solutionScope: CoroutinePuzzleSolutionScope)
fun getNumberAndSubmit(): GetNumberAndSubmit = object : GetNumberAndSubmit {
    override suspend fun getNumber(): Int = getNumber.submitCall(Unit)

    override suspend fun submit(sum: Int) {
        submitNumber.submitCall(sum)
    }
}

context(solutionScope: CoroutinePuzzleSolutionScope)
fun numberFlowAndSubmit(): NumberFlowAndSubmit = object : NumberFlowAndSubmit {
    override fun numbers(): Flow<Int> =
        flow { while (true) emit(emitNumber.submitCall(Unit) ?: break) }

    override suspend fun submit(number: Int) {
        submitNumber.submitCall(number)
    }
}

interface UserDatabase {
    suspend fun getAllIds(): List<Int>
    suspend fun queryUser(id: Int): User
    suspend fun submit(number: Int)
}

interface UserDatabaseWithLegacyQueryUser {
    suspend fun getAllIds(): List<Int>
    fun queryUserWithCallback(
        id: Int,
        onSuccess: (User) -> Unit,
        onError: (Throwable) -> Unit = { error("Query exception happened, but you didn't handle it!") },
    ): QueryHandle
    suspend fun submit(number: Int)
}

interface QueryHandle {
    fun cancel(onCancellationFinished: () -> Unit = {})
}

data class User(val name: String, val age: Int)

context(solutionScope: CoroutinePuzzleSolutionScope)
fun getUserDatabase(): UserDatabase = object : UserDatabase {
    override suspend fun getAllIds(): List<Int> = getAllUserIds.submitCall(Unit)
    override suspend fun queryUser(id: Int): User = queryUserById.submitCall(id)!!.let { User(it.name, it.age) }
    override suspend fun submit(number: Int) {
        submitNumber.submitCall(number)
    }
}

context(solutionScope: CoroutinePuzzleSolutionScope)
fun getUserDatabaseWithLegacyQueryUser(
    topLevelScope: CoroutineScope,
    cancellationHook: CompletableDeferred<Unit> = CompletableDeferred(),
): UserDatabaseWithLegacyQueryUser = object : UserDatabaseWithLegacyQueryUser {
    override suspend fun getAllIds(): List<Int> = getAllUserIds.submitCall(Unit)

    override fun queryUserWithCallback(id: Int, onSuccess: (User) -> Unit, onError: (Throwable) -> Unit): QueryHandle {
        val isDone = CompletableDeferred<Unit>()
        return topLevelScope.launch {
            try {
                queryUserById
                    .submitCall(id)
                    ?.let {
                        onSuccess(User(it.name, it.age))
                    }
                    ?: onError(QueryFetchFailedForSomeReasonException())
            } finally {
                isDone.complete(Unit)
            }
        }.let { job ->
            object : QueryHandle {
                override fun cancel(onCancellationFinished: () -> Unit) {
                    topLevelScope.launch {
                        try {
                            job.cancelAndJoin()
                            isDone.await() // I am so confused as to why this is necessary...
                        } catch (t: Throwable) {
                            t.printStackTrace()
                        } finally {
                            cancellationHook.completeAfter { onCancellationFinished() }
                        }
                    }
                }
            }
        }
    }

    override suspend fun submit(number: Int) {
        submitNumber.submitCall(number)
    }
}

class QueryFetchFailedForSomeReasonException(): Exception()
