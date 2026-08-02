package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.ExceptionalApi
import kmpworkshop.common.coroutineExceptionHandlingApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

context(_: CoroutinePuzzleSolutionScope)
suspend fun exceptionsInCoroutineHandlingScaffolding(
    doPuzzle: suspend CoroutineScope.(ExceptionalApi) -> Unit,
) {
    try {
        coroutineScope { doPuzzle(coroutineExceptionHandlingApiService()) }
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        e.printStackTrace()
    }
}
