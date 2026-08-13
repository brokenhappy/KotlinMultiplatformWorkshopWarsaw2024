package kmpworkshop.common

import kmpworkshop.api.ExceptionalApi
import kmpworkshop.common.DefaultApis.clearCachesEndpoint
import kmpworkshop.common.DefaultApis.refreshTokensEndpoint
import kmpworkshop.common.DefaultApis.reportExceptionEndpoint

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
