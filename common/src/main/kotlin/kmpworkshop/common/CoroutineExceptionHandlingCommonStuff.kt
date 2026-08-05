package kmpworkshop.common

val clearCachesEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("clearCaches()")
val refreshTokensEndpoint = coroutinePuzzleEndPoint<Unit, Unit>("refreshTokens()")
val reportExceptionEndpoint = coroutinePuzzleEndPoint<String?, Unit>("reportException(e: Exception)")

interface ExceptionalApi {
    suspend fun clearCaches()
    suspend fun refreshTokens()
    suspend fun reportException(e: Exception)
}

context(_: CoroutinePuzzleSolutionScope)
fun coroutineExceptionHandlingApiService(): ExceptionalApi = object : ExceptionalApi {
    override suspend fun clearCaches() {
        clearCachesEndpoint.submitCall(Unit)
    }

    override suspend fun refreshTokens() {
        refreshTokensEndpoint.submitCall(Unit)
    }

    override suspend fun reportException(e: Exception) {
        reportExceptionEndpoint.submitCall(e.message)
    }
}
