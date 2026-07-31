package kmpworkshop.common

import kotlinx.coroutines.launch

context(solutionScope: CoroutinePuzzleSolutionScope)
suspend fun mapFromLegacyApiWithScaffolding(
    mapFromLegacyApi: suspend (UserDatabaseWithLegacyQueryUser) -> Unit,
) {
    withImportantCleanup {
        launch {
            try {
                mapFromLegacyApi(getUserDatabaseWithLegacyQueryUser(
                    topLevelScope = this@withImportantCleanup,
                ))
            } catch (_: QueryFetchFailedForSomeReasonException) {
                importantCleanup {
                    queryExceptionThrown.submitCall(Unit)
                }
            } finally {
                importantCleanup {
                    callIsDone.submitCall(Unit)
                }
            }
        }.sideEffect {
            callLifetime.submitCall(Unit)
            it.cancel()
        }
    }
}