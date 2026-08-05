package kmpworkshop.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

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
    override suspend fun queryUser(id: Int): User = queryUserById.submitCall(id).let { User(it.name, it.age) }
    override suspend fun submit(number: Int) {
        submitNumber.submitCall(number)
    }
}

context(solutionScope: CoroutinePuzzleSolutionScope)
fun getUserDatabaseWithLegacyQueryUser(
    topLevelScope: CoroutineScope,
): UserDatabaseWithLegacyQueryUser = object : UserDatabaseWithLegacyQueryUser {
    override suspend fun getAllIds(): List<Int> = getAllUserIds.submitCall(Unit)

    override fun queryUserWithCallback(id: Int, onSuccess: (User) -> Unit, onError: (Throwable) -> Unit): QueryHandle {
        val isDone = CompletableDeferred<Unit>()
        return topLevelScope.launch {
            try {
                try {
                    queryUserById.submitCall(id).let { onSuccess(User(it.name, it.age)) }
                } catch (failure: ExceptionAcrossRpc) {
                    onError(QueryFetchFailedForSomeReasonException(failure.message))
                }
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
                            legacyCancellationCompletion.submitCall(Unit)
                        } finally {
                            onCancellationFinished()
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

class QueryFetchFailedForSomeReasonException(message: String? = null) : Exception(message)
