package kmpworkshop.client

import kmpworkshop.common.ExceptionalApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

suspend fun exceptionHandlingPuzzle(api: ExceptionalApi) {
    coroutineScope {
        try {
            launch { api.clearCaches() }
            launch { api.refreshTokens() }
        } catch (e: Exception) {
            api.reportException(e)
        }
    }
}