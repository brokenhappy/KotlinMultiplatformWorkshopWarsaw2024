package kmpworkshop.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException

context(solutionScope: CoroutinePuzzleSolutionScope)
suspend fun mapFromLegacyApiWithScaffolding(
    mapFromLegacyApi: suspend (UserDatabaseWithLegacyQueryUser) -> Unit,
) {
    withImportantCleanup {
        flow {
            emit(1)
            callLifetime.submitCall(Unit,)
            emit(2)
        }.collectLatest {
            if (it == 2) return@collectLatest
            try {
                val cancellationHook = CompletableDeferred<Unit>()
                try {
                    mapFromLegacyApi(getUserDatabaseWithLegacyQueryUser(
                        topLevelScope = this@withImportantCleanup,
                        cancellationHook = cancellationHook,
                    ))
                } catch (c: CancellationException) {
                    withImportantCleanup {
                        cancellationHook.await()
                    }
                }
            } catch (_: QueryFetchFailedForSomeReasonException) {
                importantCleanup {
                    queryExceptionThrown.submitCall(Unit,)
                }
            } finally {
                importantCleanup {
                    callIsDone.submitCall(Unit,)
                }
            }
        }
    }
}