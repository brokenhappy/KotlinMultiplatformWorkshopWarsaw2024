package kmpworkshop.solutions

import kmpworkshop.api.FileToInternetExposingApi
import kmpworkshop.api.NetworkStrengthObserver
import kmpworkshop.api.isStrong
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

private fun FileToInternetExposingApi.runOnStrongNetwork(task: suspend () -> Unit) {
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
