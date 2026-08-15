package kmpworkshop.client

import kmpworkshop.api.GetNumberAndSubmit
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.DefaultApis.callIsDone
import kmpworkshop.common.DefaultApis.callLifetime
import kmpworkshop.common.DefaultApis.getNumber
import kmpworkshop.common.DefaultApis.legacyCancellationCompletion
import kmpworkshop.common.DefaultApis.queryExceptionThrown
import kmpworkshop.common.DefaultApis.submitNumber
import kmpworkshop.common.importantCleanup
import kmpworkshop.common.sideEffect
import kmpworkshop.common.submitCall
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Observes the lifecycle around NumSumFun without adding anything to its attendee-facing API. */
context(_: CoroutinePuzzleSolutionScope)
suspend fun sumWithLifecycleScaffolding(
    sumNumbers: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
    reportEscapedCancellation: Boolean = false,
    cancelWhenLifetimeEnds: Boolean = true,
    reportCancellationCompletion: Boolean = false,
) {
    withImportantCleanup {
        launch {
            try {
                coroutineScope {
                    sumNumbers(object : GetNumberAndSubmit {
                        override suspend fun getNumber(): Int = try {
                            getNumber.submitCall(Unit)
                        } catch (cancelled: CancellationException) {
                            if (reportCancellationCompletion) {
                                importantCleanup { legacyCancellationCompletion.submitCall(Unit) }
                            }
                            throw cancelled
                        }

                        override suspend fun submit(sum: Int) {
                            submitNumber.submitCall(sum)
                        }
                    })
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException && !reportEscapedCancellation) throw failure
                importantCleanup { queryExceptionThrown.submitCall(Unit) }
            } finally {
                importantCleanup { callIsDone.submitCall(Unit) }
            }
        }.sideEffect {
            callLifetime.submitCall(Unit)
            if (cancelWhenLifetimeEnds) it.cancel() else it.join()
        }
    }
}
