package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.FileToInternetExposingApi
import kmpworkshop.common.NetworkStrength
import kmpworkshop.common.NetworkStrengthObserver
import kmpworkshop.common.callLifetime
import kmpworkshop.common.fileToInternetExposingApi
import kmpworkshop.common.importantCleanup
import kmpworkshop.common.isStrong
import kmpworkshop.common.sideEffect
import kmpworkshop.common.submitCall
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Participant-editable implementation for all three file-exposure stages. */
suspend fun allowPeopleToDownloadExposedFile(api: FileToInternetExposingApi) {
    coroutineScope {
        api.currentFileToExpose().collectLatest { file ->
            file.open()
            try {
                api.runOnStrongNetwork {
                    launch { api.advertiseFile(file) }
                    api.makeDownloadable(file)
                }
            } finally {
                file.close()
            }
        }
    }
}

fun FileToInternetExposingApi.runOnStrongNetwork(task: suspend () -> Unit) {
    var job: Job? = null
    val observer = NetworkStrengthObserver { strength ->
        if (strength.isStrong) {
            if (job?.isActive != true) job = coroutineScope.launch { task() }
        } else {
            job?.cancel()
        }
    }
    registerObserver(observer)
    coroutineScope.coroutineContext[Job]?.invokeOnCompletion {
        job?.cancel()
        unregisterObserver(observer)
    }
}

context(_: CoroutinePuzzleSolutionScope)
suspend fun fileExposureScaffolding(
    solution: suspend CoroutineScope.(FileToInternetExposingApi) -> Unit,
) {
    withImportantCleanup {
        launch {
            try {
                coroutineScope { solution(fileToInternetExposingApi(this)) }
            } finally {
                importantCleanup { /* lets adapter cleanup finish before this job completes */ }
            }
        }.sideEffect {
            callLifetime.submitCall(Unit)
            it.cancel()
        }
    }
}
