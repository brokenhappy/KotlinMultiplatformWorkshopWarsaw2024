package kmpworkshop.server

import kmpworkshop.common.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun coroutineExceptionHandlingCoroutinePuzzle(): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    val exceptionMessage = "Oh no, refreshing tokens went all doodoo for ticket number ${(0..1000).random()}"

    awaitQuiescenceAndVerifyUnmatchedSubmissions(clearCachesEndpoint, refreshTokensEndpoint) {
        CoroutinePuzzleErrorMessages.exceptionCallsMustBeConcurrent()
    }
    coroutineScope {
        launch { clearCachesEndpoint.expectCanceledCall { awaitCancellation() } }
        launch { refreshTokensEndpoint.expectThrowingCall(exceptionMessage) }
    }
    reportExceptionEndpoint.expectCall {
        verify(exceptionMessage == it) {
            CoroutinePuzzleErrorMessages.wrongReportedException(exceptionMessage, it)
        }
    }
}
