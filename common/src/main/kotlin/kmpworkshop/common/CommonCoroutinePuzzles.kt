package kmpworkshop.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.coroutines.cancellation.CancellationException


@Serializable
data class CoroutinePuzzleEndPoint<in T, out R>(val description: String)

class CoroutinePuzzleState(
    val branchCount: Int,
    val expectedCalls: List<CoroutinePuzzleEndPointWaitingState<*, *>>,
) {
    fun copy(
        branchCount: Int = this.branchCount,
        expectedCalls: List<CoroutinePuzzleEndPointWaitingState<*, *>> = this.expectedCalls,
    ): CoroutinePuzzleState = CoroutinePuzzleState(branchCount, expectedCalls)

    override fun toString(): String = """CoroutinePuzzleState(
        branchCount=$branchCount,
        currentExpectedCalls=$expectedCalls,
    )""".trimIndent()
}

class CoroutinePuzzleEndPointWaitingState<T, R>(
    val endPoint: CoroutinePuzzleEndPoint<T, R>,
    /** Used to make sure that multiple submits will not submit the same expected call */
    var isTaken: Boolean,
    /** Is called from submission side */
    val submitCall: suspend (JsonElement) -> JsonElement,
) {
    override fun toString(): String = """WaitingState(endPoint=${endPoint.description}, isTaken=$isTaken)""".trimIndent()
}

data class CoroutinePuzzle(
    val puzzle: suspend (MutableStateFlow<CoroutinePuzzleState>) -> Unit,
)

interface CoroutinePuzzleSolutionScope {
    suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement
}

context(solutionScope: CoroutinePuzzleSolutionScope)
suspend inline fun <reified T, reified R> CoroutinePuzzleEndPoint<T, R>.submitCall(t: T): R = with(solutionScope) {
    Json.decodeFromJsonElement(submitRawCall(Json.encodeToJsonElement(t)))
}

context(solutionScope: CoroutinePuzzleSolutionScope)
suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement =
    with(solutionScope) { submitRawCall(t) }

@Serializable
sealed class CoroutinePuzzleSolutionResult {
    @Serializable
    data object Success : CoroutinePuzzleSolutionResult()
    @Serializable
    data class Failure(val description: String) : CoroutinePuzzleSolutionResult()
}

context(_: CoroutinePuzzleSolutionScope)
internal fun fail(
    message: String,
    /** Whether upon printing the failure, one should add the history of actions (sometimes you rather print it manually) */
    includeHistory: Boolean = true,
): Nothing = throw CoroutinePuzzleFailedControlFlowException(message, includeHistory = false)

class CoroutinePuzzleFailedControlFlowException(
    message: String,
    val includeHistory: Boolean,
) : Exception(message, null) // TODO: Optimize away stacktrace hydration?

suspend fun CoroutinePuzzle.solve(solution: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit): CoroutinePuzzleSolutionResult {
    val history = MutableStateFlow<List<CoroutinePuzzleEndPoint<*, *>>>(emptyList())
    val stateRemoverLock = Mutex()
    return try {
        coroutineScope {
            val puzzleState = MutableStateFlow(CoroutinePuzzleState(branchCount = 1, expectedCalls = emptyList()))
            launch {
                this@solve.puzzle(puzzleState)
            }
            object : CoroutinePuzzleSolutionScope {
                override suspend fun CoroutinePuzzleEndPoint<*, *>.submitRawCall(t: JsonElement): JsonElement {
                    val state = puzzleState.mapNotNull { currentState ->
//                        println("${coroutineContext.job.hashCode()} => $currentState")
                        val expectedCalls = currentState.expectedCalls.filterNot { it.isTaken }
                        if (currentState.branchCount < currentState.expectedCalls.size) error("Wth happened?")
                        else stateRemoverLock.withLock {
                            expectedCalls
                                .firstOrNull { it.endPoint == this }
                                ?.also { it.isTaken = true }
                                .also {
                                    if (it == null && currentState.branchCount == expectedCalls.size) fail("""
                                        |The history of actions was:
                                        |${history.value.joinToString("\n") { it.description }}
                                        ${
                                            when (expectedCalls.size) {
                                                0 -> "|And no more actions were expected to be made."
                                                1 -> "|And now the expected action is: ${expectedCalls.first().endPoint.description}"
                                                else -> """
                                                    |And now the expected actions are either:
                                                    |${expectedCalls.joinToString(",\n or ") { it.endPoint.description }}
                                                """.trimIndent()
                                            }
                                        }
                                        |But instead you did: $description
                                    """.trimMargin(), includeHistory = false)
                                }
                        }
                    }.first()

                    return state.submitCall.also { history.update { it + this@submitRawCall } }.invoke(t)
                }
            }.let { coroutineScope { context(it) { solution(this) } } }

            fun failBecauseLeftovers(message: String): Nothing =
                throw CoroutinePuzzleFailedControlFlowException(message, includeHistory = true)
            if (history.value.isEmpty()) puzzleState.first { it.expectedCalls.isNotEmpty() } // Wait for first expected call
            puzzleState.first { it.expectedCalls.size == it.branchCount } // Wait to reach all expect calls
            val leftoverExpectedCalls = stateRemoverLock.withLock {
                puzzleState.value.expectedCalls.filterNot { it.isTaken }
            }
            when (leftoverExpectedCalls.size) {
                0 -> { /* All is OK! */ }
                1 -> failBecauseLeftovers(
                    "You made too few function calls. We're still expecting: ${leftoverExpectedCalls.single().endPoint.description}"
                )
                else -> failBecauseLeftovers("""
                    |You made too few function calls, we were expecting one of:
                    |${leftoverExpectedCalls.joinToString(",\n or ") { it.endPoint.description }}
                """.trimMargin())
            }
            CoroutinePuzzleSolutionResult.Success
        }
    } catch (e: CoroutinePuzzleFailedControlFlowException) {
        CoroutinePuzzleSolutionResult.Failure("""
            |${e.message}
            |${
            if (e.includeHistory) """
                    |The history of actions was:
                    |${history.value.joinToString("\n") { it.description }}
                """.trimMargin() else ""
        }
        """.trimMargin())
    }
}

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
        try {
            submitNumber.submitCall(sum)
        } catch (e: CancellationException) {
            importantCleanup {
                cancelSubmit.submitCall(Unit)
            }
            throw e
        }
    }
}

context(solutionScope: CoroutinePuzzleSolutionScope)
fun numberFlowAndSubmit(): NumberFlowAndSubmit = object : NumberFlowAndSubmit {
    override fun numbers(): Flow<Int> =
        flow { while (true) emit(emitNumber.submitCall(Unit) ?: break) }

    override suspend fun submit(number: Int) {
        try {
            submitNumber.submitCall(number)
        } catch (e: CancellationException) {
            importantCleanup {
                cancelSubmit.submitCall(Unit)
            }
            throw e
        }
    }
}

interface UserDatabase {
    suspend fun getAllIds(): List<Int>
    suspend fun queryUser(id: Int): User
    suspend fun submit(number: Int)
}

data class User(val name: String, val age: Int)

context(solutionScope: CoroutinePuzzleSolutionScope)
fun getUserDatabase(): UserDatabase = object : UserDatabase {
    override suspend fun getAllIds(): List<Int> = getAllUserIds.submitCall(Unit)
    override suspend fun queryUser(id: Int): User = queryUserById.submitCall(id).let { User(it.name, it.age) }
    override suspend fun submit(number: Int) {
        try {
            submitNumber.submitCall(number)
        } catch (e: CancellationException) {
            importantCleanup {
                cancelSubmit.submitCall(Unit)
            }
            throw e
        }
    }
}