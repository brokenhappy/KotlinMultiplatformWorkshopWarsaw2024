package kmpworkshop.server

import kmpworkshop.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun coroutineExceptionHandlingCoroutinePuzzle(): Resource<CoroutinePuzzleProtocol> = coroutinePuzzle {
    val exceptionMessage = "Oh no, refreshing tokens went all doodoo for ticket number ${(0..1000).random()}"
    val clearCachesCompleted = CompletableDeferred<Unit>()
    expectingMatchedParallelism {
        coroutineScope {
            launch {
                clearCachesEndpoint.expectCall(Unit)
                clearCachesCompleted.complete(Unit)
            }
            launch {
                refreshTokensEndpoint.expectCall {
                    clearCachesCompleted.await()
                    exceptionMessage
                }
            }
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
}
