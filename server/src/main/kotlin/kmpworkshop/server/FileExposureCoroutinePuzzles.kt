package kmpworkshop.server

import kmpworkshop.common.*
import kmpworkshop.api.FakeFileId
import kmpworkshop.api.NetworkStrength
import kmpworkshop.common.DefaultApis
import kmpworkshop.common.DefaultApis.advertiseExposedFile
import kmpworkshop.common.DefaultApis.callLifetime
import kmpworkshop.common.DefaultApis.closeExposedFile
import kmpworkshop.common.DefaultApis.emitFileToExpose
import kmpworkshop.common.DefaultApis.emitNetworkStrength
import kmpworkshop.common.DefaultApis.makeFileDownloadable
import kmpworkshop.common.DefaultApis.openExposedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun fileExposureStepOnePuzzle() = coroutinePuzzle {
    emitFileToExpose.expectingFlowCollector().use { fileCollectors ->
        emitNetworkStrength.expectingFlowCollector().use { networkCollectors ->
            fileCollectors.use { (_, emitFile) ->
                networkCollectors.use { (_, emitNetwork) -> evaluateNetworkRestart(emitFile, emitNetwork) }
            }
        }
    }
}
fun fileExposureStepTwoPuzzle() = coroutinePuzzle {
    emitFileToExpose.expectingFlowCollector().use { fileCollectors ->
        emitNetworkStrength.expectingFlowCollector().use { networkCollectors ->
            fileCollectors.use { (_, emitFile) ->
                networkCollectors.use { (_, emitNetwork) -> evaluateFileReplacement(false, emitFile, emitNetwork) }
            }
        }
    }
}
fun fileExposureStepThreePuzzle() = coroutinePuzzle {
    emitFileToExpose.expectingFlowCollector().use { fileCollectors ->
        emitNetworkStrength.expectingFlowCollector().use { networkCollectors ->
            fileCollectors.use { (_, emitFile) ->
                networkCollectors.use { (_, emitNetwork) -> evaluateFileReplacement(true, emitFile, emitNetwork) }
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
    awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList()) {
        if (advertiseExposedFile in it || makeFileDownloadable in it) {
            CoroutinePuzzleErrorMessages.weakWifiExposureStarted()
        } else {
            null
        }
    }
    emitNetwork(NetworkStrength.WifiStrong)
    launch {
        advertiseExposedFile.expectCanceledCall {
            verify(it == file) { CoroutinePuzzleErrorMessages.wrongFile("advertise", "the opened file") }
            awaitCancellation() // We don't care when it cancels. That's a problem for later.
        }
    }
    makeFileDownloadable.expectCanceledCall {
        verify(it == file) { CoroutinePuzzleErrorMessages.wrongFile("make downloadable", "the opened file") }
        runOnBiggerScope(this@coroutineScope) {
            emitNetwork(NetworkStrength.WifiWeak)
            emitNetwork(NetworkStrength.WifiStrong)
            awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList()) {
                CoroutinePuzzleErrorMessages.networkRestartStartedTooEarly(it)
            }
        }
        awaitCancellation()
    }
    makeFileDownloadable.expectCanceledCall {
        advertiseExposedFile.expectCall {
            verify(it == file) { CoroutinePuzzleErrorMessages.wrongFile("advertise", "the opened file") }
            Unit // Advertising cancellation is deliberately not part of this stage.
        }
        awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList())
        callLifetimeSignal.complete(Unit) // Cancel only after the advertising result was consumed.
        awaitCancellation()
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
    coroutineScope {
        val scopeToLaunchOn = if (cancelAdvertising) this else this@evaluatorScope
        scopeToLaunchOn.launch {
            advertiseExposedFile.expectCanceledCall {
                verify(it == first) { CoroutinePuzzleErrorMessages.wrongFile("advertise", "the first opened file") }
                awaitCancellation()
            }
        }
        makeFileDownloadable.expectCanceledCall {
            verify(it == first) { CoroutinePuzzleErrorMessages.wrongFile("make downloadable", "the first opened file") }
            runOnBiggerScope(this@evaluatorScope) {
                emitFile(second)
            }
            awaitCancellation()
        }
    }

    closeExposedFile.expectArgument(first)
    openExposedFile.expectArgument(second)
    emitNetwork(NetworkStrength.WifiStrong)

    makeFileDownloadable.expectCanceledCall {
        verify(it == second) { CoroutinePuzzleErrorMessages.wrongFile("make downloadable", "the replacement file") }
        advertiseExposedFile.expectCall {
            verify(it == second) { CoroutinePuzzleErrorMessages.wrongFile("advertise", "the replacement file") }
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
    verify(actual == expected) { CoroutinePuzzleErrorMessages.wrongEndpointArgument(expected, actual) }
}
