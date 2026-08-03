package kmpworkshop.server

import kmpworkshop.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun fileExposureStepOnePuzzle() = coroutinePuzzle { evaluateNetworkRestart() }
fun fileExposureStepTwoPuzzle() = coroutinePuzzle { evaluateFileReplacement(cancelAdvertising = false) }
fun fileExposureStepThreePuzzle() = coroutinePuzzle { evaluateFileReplacement(cancelAdvertising = true) }

context(_: CoroutinePuzzleBuilderScope)
private suspend fun evaluateNetworkRestart(): Unit = coroutineScope {
    val callLifetimeSignal = CompletableDeferred<Unit>()
    launch {
        callLifetime.expectCall {
            callLifetimeSignal.await()
        }
    }

    val file = FakeFileId((0..999).random())
    emitFileToExpose.expectCall(file)
    // We will never emit again. So we'll just accept the call and let it get canceled with the rest.
    // The reason we do this early is so that they won't be unmatched submissions that complicate our assertions down the line.
    launch { emitFileToExpose.expectCanceledCall { awaitCancellation() } }
    openExposedFile.expectArgument(file)
    // Will happen again sometime quite soon, because they don't use structured concurrency, but we don't care about that in this stage yet.
    launch { closeExposedFile.expectArgument(file) }
    emitNetworkStrength.expectCall(NetworkStrength.WifiWeak)
    awaitQuiescenceAndVerifyUnmatchedSubmissions(emitNetworkStrength) {
        if (advertiseExposedFile in it || makeFileDownloadable in it) {
            "The WiFi is weak, but you're already trying to expose the file!"
        } else {
            null
        }
    }
    emitNetworkStrength.expectCall(NetworkStrength.WifiStrong)
    launch {
        advertiseExposedFile.expectCanceledCall {
            verify(it == file) { "The advertised file must be opened" }
            awaitCancellation() // We don't care when it cancels. That's a problem for later.
        }
    }
    makeFileDownloadable.expectCanceledCall {
        verify(it == file) { "The exposed file must be opened" }
        runOnBiggerScope(this@coroutineScope) {
            emitNetworkStrength.expectCall(NetworkStrength.WifiWeak)
            emitNetworkStrength.expectCall(NetworkStrength.WifiStrong)
            // We won't emit again, so we'll just accept the call and let it get canceled with the rest.
            this@coroutineScope.launch { emitNetworkStrength.expectCanceledCall { awaitCancellation() } }
            awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList()) {
                """
                    The WiFi signal went weak and strong again.
                    Now you're supposed to wait until cancellation is complete before you can expose the file again.
                    However, you already started doing the following actions:
                    ${it.joinToString("\n                    ") { " - ${it.descriptor.description}" }}
                """.trimIndent()
            }
        }
        awaitCancellation()
    }
    makeFileDownloadable.expectCanceledCall {
        advertiseExposedFile.expectCall {
            verify(it == file) { "The advertised file must be opened" }
            Unit // Advertising cancellation is deliberately not part of this stage.
        }
        awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList())
        callLifetimeSignal.complete(Unit) // Cancel only after the advertising result was consumed.
        awaitCancellation()
    }
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun evaluateFileReplacement(cancelAdvertising: Boolean): Unit = coroutineScope evaluatorScope@ {
    val callLifetimeSignal = CompletableDeferred<Unit>()
    launch { callLifetime.expectCall { callLifetimeSignal.await() } }

    val first = FakeFileId((0..999).random())
    emitFileToExpose.expectCall(first)
    openExposedFile.expectArgument(first)
    emitNetworkStrength.expectCall(NetworkStrength.WifiStrong)

    val second = FakeFileId((1000..1999).random())
    coroutineScope {
        val scopeToLaunchOn = if (cancelAdvertising) this else this@evaluatorScope
        scopeToLaunchOn.launch {
            advertiseExposedFile.expectCanceledCall {
                verify(it == first) { "The advertised file must be the first opened file" }
                awaitCancellation()
            }
        }
        makeFileDownloadable.expectCanceledCall {
            verify(it == first) { "The downloadable file must be the first opened file" }
            runOnBiggerScope(this@evaluatorScope) {
                emitFileToExpose.expectCall(second)
            }
            awaitCancellation()
        }
    }

    closeExposedFile.expectArgument(first)
    openExposedFile.expectArgument(second)
    emitNetworkStrength.expectCall(NetworkStrength.WifiStrong)

    // These loops have no more values to emit. Keep their final calls paired until lifetime teardown.
    launch { emitFileToExpose.expectCanceledCall { awaitCancellation() } }
    launch { emitNetworkStrength.expectCanceledCall { awaitCancellation() } }

    makeFileDownloadable.expectCanceledCall {
        verify(it == second) { "The downloadable file must be the replacement file" }
        advertiseExposedFile.expectCall {
            verify(it == second) { "The advertised file must be the replacement file" }
            Unit
        }
        awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList())
        callLifetimeSignal.complete(Unit)
        awaitCancellation()
    }
    closeExposedFile.expectArgument(second)
}

context(_: CoroutinePuzzleBuilderScope)
private suspend inline fun <reified T> CoroutinePuzzleEndPoint<T, Unit>.expectArgument(expected: T) {
    val actual = expectCall(Unit)
    verify(actual == expected) { "Expected $expected, but received $actual" }
}
