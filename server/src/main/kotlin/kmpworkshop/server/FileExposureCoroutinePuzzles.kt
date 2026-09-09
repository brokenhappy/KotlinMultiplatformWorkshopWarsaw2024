package kmpworkshop.server

import kmpworkshop.api.FakeFileId
import kmpworkshop.api.NetworkStrength
import kmpworkshop.common.CoroutinePuzzleEndPoint
import kmpworkshop.common.DefaultApis.advertiseExposedFile
import kmpworkshop.common.DefaultApis.callLifetime
import kmpworkshop.common.DefaultApis.closeExposedFile
import kmpworkshop.common.DefaultApis.emitFileToExpose
import kmpworkshop.common.DefaultApis.emitNetworkStrength
import kmpworkshop.common.DefaultApis.makeFileDownloadable
import kmpworkshop.common.DefaultApis.openExposedFile
import kmpworkshop.common.UnmatchedSubmission
import kmpworkshop.common.runOnBiggerScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun fileExposureStepOnePuzzle() = fileExposurePuzzle { emitFile, emitNetwork ->
    evaluateNetworkRestart(emitFile, emitNetwork)
}
fun fileExposureStepTwoPuzzle() = fileExposurePuzzle { emitFile, emitNetwork ->
    evaluateFileReplacement(false, emitFile, emitNetwork)
}
fun fileExposureStepThreePuzzle() = fileExposurePuzzle { emitFile, emitNetwork ->
    evaluateFileReplacement(true, emitFile, emitNetwork)
}

private fun fileExposurePuzzle(
    evaluate: suspend context(CoroutinePuzzleBuilderScope) (
        emitFile: suspend (FakeFileId) -> Unit,
        emitNetwork: suspend (NetworkStrength) -> Unit,
    ) -> Unit,
) = coroutinePuzzle {
    emitFileToExpose.expectingFlowCollector().use { fileCollectors ->
        emitNetworkStrength.expectingFlowCollector().use { networkCollectors ->
            fileCollectors.use { (_, emitFile) ->
                networkCollectors.use { (_, emitNetwork) ->
                    evaluate(emitFile, emitNetwork)
                }
            }
        }
    }
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun evaluateNetworkRestart(
    emitFile: suspend (FakeFileId) -> Unit,
    emitNetwork: suspend (NetworkStrength) -> Unit,
): Unit = coroutineScope {
    val callLifetimeSignal = CompletableDeferred<Unit>()
    launch {
        callLifetime.expectCall {
            callLifetimeSignal.await()
        }
    }

    val file = FakeFileId((0..999).random())
    emitFile(file)
    openExposedFile.expectArgument(file)
    // Will happen again sometime quite soon, because they don't use structured concurrency, but we don't care about that in this stage yet.
    launch { closeExposedFile.expectArgument(file) }
    emitNetwork(NetworkStrength.WifiWeak)
    verifyUnmatchedSubmissions(awaitFileExposureCalls(), emptyList()) {
        if (it.any { submission ->
                submission.endPoint == advertiseExposedFile || submission.endPoint == makeFileDownloadable
            }) {
            CoroutinePuzzleErrorMessages.weakWifiExposureStarted()
        } else {
            null
        }
    }
    emitNetwork(NetworkStrength.WifiStrong)
    launch {
        advertiseExposedFile.expectCanceledCall {
            verify(it == file) { CoroutinePuzzleErrorMessages.wrongFile("advertise", "the opened file") }
            expectCancellation() // We don't care when it cancels. That's a problem for later.
        }
    }
    val downloadStarted = CompletableDeferred<Unit>()
    val networkRestart = launch {
        downloadStarted.await()
        emitNetwork(NetworkStrength.WifiWeak)
        emitNetwork(NetworkStrength.WifiStrong)
    }
    makeFileDownloadable.expectCanceledCall {
        verify(it == file) { CoroutinePuzzleErrorMessages.wrongFile("make downloadable", "the opened file") }
        runOnBiggerScope(this@coroutineScope) {
            downloadStarted.complete(Unit)
            verifyUnmatchedSubmissions(awaitFileExposureCalls(), emptyList()) {
                CoroutinePuzzleErrorMessages.networkRestartStartedTooEarly(it.map { submission -> submission.endPoint })
            }
        }
        expectCancellation()
    }
    networkRestart.join()
    makeFileDownloadable.expectCanceledCall {
        advertiseExposedFile.expectCall {
            verify(it == file) { CoroutinePuzzleErrorMessages.wrongFile("advertise", "the opened file") }
            // Advertising cancellation is deliberately not part of this stage.
        }
        verifyUnmatchedSubmissions(awaitFileExposureCalls(), emptyList())
        callLifetimeSignal.complete(Unit) // Cancel only after the advertising result was consumed.
        expectCancellation()
    }
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun evaluateFileReplacement(
    cancelAdvertising: Boolean,
    emitFile: suspend (FakeFileId) -> Unit,
    emitNetwork: suspend (NetworkStrength) -> Unit,
): Unit = coroutineScope evaluatorScope@ {
    val callLifetimeSignal = CompletableDeferred<Unit>()
    launch { callLifetime.expectCall { callLifetimeSignal.await() } }

    val first = FakeFileId((0..999).random())
    emitFile(first)
    openExposedFile.expectArgument(first)
    emitNetwork(NetworkStrength.WifiStrong)

    val second = FakeFileId((1000..1999).random())
    val firstDownloadStarted = CompletableDeferred<Unit>()
    val replacement = launch {
        firstDownloadStarted.await()
        emitFile(second)
    }
    coroutineScope {
        val scopeToLaunchOn = if (cancelAdvertising) this else this@evaluatorScope
        scopeToLaunchOn.launch {
            advertiseExposedFile.expectCanceledCall {
                verify(it == first) { CoroutinePuzzleErrorMessages.wrongFile("advertise", "the first opened file") }
                expectCancellation()
            }
        }
        makeFileDownloadable.expectCanceledCall {
            verify(it == first) { CoroutinePuzzleErrorMessages.wrongFile("make downloadable", "the first opened file") }
            firstDownloadStarted.complete(Unit)
            expectCancellation()
        }
    }

    closeExposedFile.expectArgument(first)
    openExposedFile.expectArgument(second)
    replacement.join()
    emitNetwork(NetworkStrength.WifiStrong)

    makeFileDownloadable.expectCanceledCall {
        verify(it == second) { CoroutinePuzzleErrorMessages.wrongFile("make downloadable", "the replacement file") }
        advertiseExposedFile.expectCall {
            verify(it == second) { CoroutinePuzzleErrorMessages.wrongFile("advertise", "the replacement file") }
        }
        verifyUnmatchedSubmissions(awaitFileExposureCalls(), emptyList())
        callLifetimeSignal.complete(Unit)
        expectCancellation()
    }
    closeExposedFile.expectArgument(second)
}

context(_: CoroutinePuzzleBuilderScope)
private suspend inline fun <reified T> CoroutinePuzzleEndPoint<T, Unit>.expectArgument(expected: T) {
    val actual = expectCall(Unit)
    verify(actual == expected) { CoroutinePuzzleErrorMessages.wrongEndpointArgument(expected, actual) }
}

/** File and network sources may already be waiting for their next value while work is being checked. */
context(_: CoroutinePuzzleBuilderScope)
private suspend fun awaitFileExposureCalls(): List<UnmatchedSubmission<*>> =
    awaitQuiescenceAndGetUnmatchedSubmissions().filterNot {
        it.endPoint == emitFileToExpose || it.endPoint == emitNetworkStrength
    }
