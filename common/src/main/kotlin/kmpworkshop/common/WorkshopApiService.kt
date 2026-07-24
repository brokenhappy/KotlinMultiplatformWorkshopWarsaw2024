@file:OptIn(ExperimentalAtomicApi::class, kotlin.time.ExperimentalTime::class)

package kmpworkshop.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.coroutines.cancellation.CancellationException

interface WorkshopServer {
    fun currentStage(): Flow<WorkshopStage>
    fun doPuzzleSolveAttempt(puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus>
    suspend fun doCoroutinePuzzleSolveAttempt(
        puzzleId: String,
        callback: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit,
    ): CoroutinePuzzleSolutionResult
}

@Rpc interface WorkshopApiService {
    suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult
    suspend fun verifyRegistration(key: ApiKey): NameVerificationResult
    fun currentStage(): Flow<WorkshopStage>
    /**
     * The batching/quiescence detection for the *solution* lives on the client (see [asServer]): the client watches
     * the user's own code go quiescent, forms a batch, and sends it here. The server only owns the *puzzle* side
     * (the expectation quiescence detection) and the matching of incoming batches against those expectations.
     */
    fun doCoroutinePuzzleSolveAttempt(key: ApiKey, puzzleId: String, messages: Flow<CoroutinePuzzleClientMessage>): Flow<CoroutinePuzzleServerMessage>
    fun doPuzzleSolveAttempt(key: ApiKey, puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus>
}

fun WorkshopApiService.asServer(
    apiKey: ApiKey,
    rpcBoundaryIdle: StateFlow<Boolean> = MutableStateFlow(true),
) = object : WorkshopServer {
    override fun currentStage(): Flow<WorkshopStage> = this@asServer.currentStage()
    override fun doPuzzleSolveAttempt(puzzleName: String, answers: Flow<JsonElement>): Flow<SolvingStatus> =
        this@asServer.doPuzzleSolveAttempt(apiKey, puzzleName, answers)

    override suspend fun doCoroutinePuzzleSolveAttempt(
        puzzleId: String,
        callback: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit,
    ): CoroutinePuzzleSolutionResult = coroutineScope toplevel@{
        // The solution-side batching detection runs *here*, on the client, wrapping the user's real code.
        // Each detected batch is sent to the server as a single [CoroutinePuzzleClientMessage.Batch]; the server
        // matches it against its expectations and streams back per-call answers.
        val outgoing = Channel<CoroutinePuzzleClientMessage>(Channel.UNLIMITED)
        val pending = ConcurrentHashMap<Int, CompletableDeferred<CallAnswer>>()
        val callIdCounter = AtomicInt(0)
        val finalResult = CompletableDeferred<CoroutinePuzzleSolutionResult>()
        val backendQuiescent = MutableStateFlow(false)
        val batchingIdle = MutableStateFlow(false)
        val frontendQuiescent = combine(batchingIdle, rpcBoundaryIdle) { batching, boundary -> batching && boundary }
        val quiescenceObserver = launch {
            frontendQuiescent.distinctUntilChanged().collect {
                outgoing.send(CoroutinePuzzleClientMessage.FrontendQuiescence(it))
            }
        }

        val submissionFunction = AutoBatchedFunctionId<SubmissionCall, JsonElement?>(
            batchResumer = { batch ->
                val wired = batch.map { call ->
                    val callId = callIdCounter.fetchAndIncrement()
                    val answer = CompletableDeferred<CallAnswer>()
                    pending[callId] = answer
                    Triple(callId, call, answer)
                }
                outgoing.send(CoroutinePuzzleClientMessage.Batch(wired.map { (callId, call, _) ->
                    CoroutinePuzzleBatchedCall(callId, call.query.endPoint.descriptor, call.query.argument)
                }))
                wired.forEach { (callId, call, answer) ->
                    // Awaiting the answer runs on the outer scope (off the intercepting dispatcher), so a long-running
                    // server call does not keep the solution from going quiescent again.
                    val job = this@toplevel.launch {
                        call.continuation.resumeWith(runCatching {
                            when (val serverAnswer = answer.await()) {
                                is CallAnswer.Success -> serverAnswer.content
                                CallAnswer.Retry -> null // Not matched: the batched() retry loop will re-batch it.
                                CallAnswer.Canceled -> throw CancellationException("Call was canceled")
                                CallAnswer.Exceptional -> throw Exception("500: Internal server error... :(")
                            }
                        })
                    }
                    call.invokeOnCancellation {
                        job.cancel()
                        pending.remove(callId)
                        outgoing.trySend(CoroutinePuzzleClientMessage.CancelCall(callId))
                    }
                }
            }
        )

        val solutionJob = launch {
            try {
                context(
                    object : CoroutinePuzzleSolutionScope {
                        override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement {
                            while (true) {
                                return submissionFunction.batched(SubmissionCall(this, t)) ?: continue
                            }
                        }
                    },
                ) {
                    submissionFunction.autoBatchedOnQuiescence(
                        quiescence = batchingIdle,
                        awaitFlushPermission = { backendQuiescent.first { it } },
                    ) { callback() }
                }
            } catch (e: CancellationException) {
                throw e // Structured cancellation (e.g. the router cancelled us after the server sent its verdict).
            } catch (e: Throwable) {
                // The user's own solution threw. Don't tear down the whole RPC with it: the *puzzle* owns the verdict,
                // and the server will report a Failure reflecting the calls that were (or weren't) made. This mirrors
                // in-process `solve`, where such an exception loses the race to the puzzle-side control-flow failure.
                e.printStackTrace()
            } finally {
                quiescenceObserver.cancel()
                outgoing.close() // Closing the message stream signals to the server that the solution is done.
            }
        }

        val router = launch {
            this@asServer.doCoroutinePuzzleSolveAttempt(apiKey, puzzleId, outgoing.consumeAsFlow()).collect { message ->
                when (message) {
                    is CoroutinePuzzleServerMessage.CallAnswered -> pending.remove(message.callId)?.complete(message.answer)
                    is CoroutinePuzzleServerMessage.BackendQuiescence -> backendQuiescent.value = message.isQuiescent
                    is CoroutinePuzzleServerMessage.Done -> {
                        finalResult.complete(message.result)
                        solutionJob.cancel() // In case the server finished (e.g. a failure) while the solution is still suspended.
                    }
                    CoroutinePuzzleServerMessage.IncorrectInput -> finalResult.completeExceptionally(
                        IllegalStateException(
                            "You accidentally made changes to the puzzle types or scaffolding.\n" +
                                "Please revert those changes yourself or ask the workshop host for help!"
                        )
                    )
                    CoroutinePuzzleServerMessage.AlreadySolved -> finalResult.complete(
                        CoroutinePuzzleSolutionResult.Failure(emptyList(), CoroutinePuzzleSolutionResult.Failure.Reason.Custom("You have already solved this puzzle!"))
                    )
                    CoroutinePuzzleServerMessage.PuzzleNotOpenedYet -> finalResult.complete(
                        CoroutinePuzzleSolutionResult.Failure(emptyList(), CoroutinePuzzleSolutionResult.Failure.Reason.Custom("The puzzle has not been opened yet!"))
                    )
                }
            }
        }

        solutionJob.join()
        finalResult.await().also { router.cancel() }
    }
}

@Serializable
enum class WorkshopStage(val kotlinFile: String) {
    Registration("Registration.kt"),
    PalindromeCheckTask("PalindromeCheck.kt"),
    FindMinimumAgeOfUserTask("MinimumAgeFinding.kt"),
    FindOldestUserTask("OldestUserFinding.kt"),
    SumOfTwoIntsSlow("NumSumFun.kt"),
    SumOfTwoIntsFast("NumSumFun.kt"),
    FindMaximumAgeCoroutines("MaximumAgeFindingWithCoroutines.kt"),
    FastFindMaximumAgeCoroutines("MaximumAgeFindingWithCoroutines.kt"),
    MappingFromLegacyApisStepOne("MappingFromLegacyApisStepOne.kt"),
    MappingFromLegacyApisStepTwo("MappingFromLegacyApisStepOne.kt"),
    MappingFromLegacyApisStepThree("MappingFromLegacyApisStepOne.kt"),
    MappingFromLegacyApisStepFour("MappingFromLegacyApisStepOne.kt"),
    SimpleFlow("FlowShow.kt"),
    CollectLatest("FlowShow.kt"),
}

@Serializable
data class SerializableColor(val red: Int, val green: Int, val blue: Int)

/** Client -> server. The client streams whole detected batches (plus per-call cancellations). */
@Serializable
sealed class CoroutinePuzzleClientMessage {
    /** A batch of submissions the client detected as simultaneously outstanding (a quiescence point). */
    @Serializable
    data class Batch(val calls: List<CoroutinePuzzleBatchedCall>) : CoroutinePuzzleClientMessage()
    @Serializable
    data class CancelCall(val callId: Int) : CoroutinePuzzleClientMessage()
    @Serializable
    data class FrontendQuiescence(val isQuiescent: Boolean) : CoroutinePuzzleClientMessage()
}

@Serializable
data class CoroutinePuzzleBatchedCall(
    val callId: Int,
    val descriptor: CoroutinePuzzleEndPointDescriptor, // Should be: CoroutinePuzzleEndpoint<*, *>, but that crashes the kotlinx compiler :sweat_smile:
    val argument: JsonElement,
)

/** Server -> client. Per-call answers, followed by a single terminal message. */
@Serializable
sealed class CoroutinePuzzleServerMessage {
    @Serializable
    data class CallAnswered(val callId: Int, val answer: CallAnswer) : CoroutinePuzzleServerMessage()
    @Serializable
    data class BackendQuiescence(val isQuiescent: Boolean) : CoroutinePuzzleServerMessage()
    @Serializable
    data class Done(val result: CoroutinePuzzleSolutionResult) : CoroutinePuzzleServerMessage()
    @Serializable
    data object IncorrectInput : CoroutinePuzzleServerMessage()
    @Serializable
    data object PuzzleNotOpenedYet : CoroutinePuzzleServerMessage()
    @Serializable
    data object AlreadySolved : CoroutinePuzzleServerMessage()
}

@Serializable
sealed class CallAnswer {
    @Serializable
    data class Success(val content: JsonElement) : CallAnswer()
    /** The submission didn't match any current expectation; the client should re-batch it. */
    @Serializable
    data object Retry : CallAnswer()
    @Serializable
    data object Canceled : CallAnswer()
    @Serializable
    data object Exceptional : CallAnswer()
}

@Serializable
sealed class SolvingStatus {
    @Serializable
    data class Next(val questionJson: JsonElement) : SolvingStatus()
    @Serializable
    data class Failed(val input: JsonElement, val actual: JsonElement, val expected: JsonElement) : SolvingStatus()
    @Serializable
    data object IncorrectInput : SolvingStatus()
    @Serializable
    data object InvalidApiKey : SolvingStatus()
    @Serializable
    data object PuzzleNotOpenedYet : SolvingStatus()
    @Serializable
    data object AlreadySolved : SolvingStatus()
    @Serializable
    data object Done : SolvingStatus()
}

@Serializable
sealed class PuzzleCompletionResult {
    @Serializable
    data object PuzzleNotOpenedYet : PuzzleCompletionResult()
    @Serializable
    data object AlreadySolved : PuzzleCompletionResult()
    @Serializable
    data object Done : PuzzleCompletionResult()
}

@Serializable
sealed class ApiKeyRegistrationResult {
    @Serializable
    data class Success(val key: ApiKey) : ApiKeyRegistrationResult()
    @Serializable
    data object NameAlreadyExists : ApiKeyRegistrationResult()
    @Serializable
    data object NameTooComplex : ApiKeyRegistrationResult()
}

@Serializable
sealed class NameVerificationResult {
    @Serializable
    data object Success : NameVerificationResult()
    @Serializable
    data object ApiKeyDoesNotExist : NameVerificationResult()
    @Serializable
    data object AlreadyRegistered : NameVerificationResult()
    @Serializable
    data object NameAlreadyExists : NameVerificationResult()
}

@Serializable
data class ApiKey(val stringRepresentation: String)

// We don't want to burden the user with @Serializable, so we hide it here
@Serializable
data class SerializableUser(val name: String, val age: Int) {
    override fun toString(): String = "User(name=$name, age=$age)"
}

fun accidentalChangesMadeError(): Nothing =
    error("You accidentally made changes to the puzzle types or scaffolding.\nPlease revert those changes yourself or ask the workshop host for help!")
