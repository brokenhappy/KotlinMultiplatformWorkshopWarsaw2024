package kmpworkshop.server

import kmpworkshop.common.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun coroutineExceptionHandlingCoroutinePuzzle(): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    val exceptionMessage = "Oh no, refreshing tokens went all doodoo for ticket number ${(0..1000).random()}"

    awaitQuiescenceAndVerifyUnmatchedSubmissions(clearCachesEndpoint, refreshTokensEndpoint)
    coroutineScope {
        launch { clearCachesEndpoint.expectCanceledCall { awaitCancellation() } }
        launch { refreshTokensEndpoint.expectCall(exceptionMessage) }
    }
    reportExceptionEndpoint.expectCall {
        verify(exceptionMessage == it) {
            """
                Oops, ${refreshTokensEndpoint.descriptor} threw an exception with text: "$exceptionMessage".
                But you reported an exception with text: ${it?.let { "\"$it\"" }}.
            """.trimIndent()
        }
    }
}
