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
            callLifetime.submitCall(Unit)
            emit(2)
        }.collectLatest {
            if (it == 2) return@collectLatest
            try {
                mapFromLegacyApi(getUserDatabaseWithLegacyQueryUser(
                    topLevelScope = this@withImportantCleanup,
                ))
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