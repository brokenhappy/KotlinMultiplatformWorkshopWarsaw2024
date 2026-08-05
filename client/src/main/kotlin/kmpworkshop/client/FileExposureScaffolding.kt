package kmpworkshop.client

import kmpworkshop.api.FileToInternetExposingApi
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.callLifetime
import kmpworkshop.common.fileToInternetExposingApi
import kmpworkshop.common.importantCleanup
import kmpworkshop.common.sideEffect
import kmpworkshop.common.submitCall
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
