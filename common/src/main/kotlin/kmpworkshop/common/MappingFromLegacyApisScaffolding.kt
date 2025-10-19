package kmpworkshop.common

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow

context(solutionScope: CoroutinePuzzleSolutionScope)
suspend fun mapFromLegacyApiWithScaffolding(
    database: UserDatabaseWithLegacyQueryUser,
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
                mapFromLegacyApi(database)
            } catch (_: QueryFetchFailedForSomeReasonException) {
                importantCleanup {
                    queryExceptionThrown.submitCall(Unit)
                }
            } finally {
                importantCleanup {
                    callIsDone.submitCall(Unit)
                }
            }
        }
    }
}