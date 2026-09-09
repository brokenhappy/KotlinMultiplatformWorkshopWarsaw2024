package kmpworkshop.client

import kmpworkshop.api.UserDatabaseWithLegacyQueryUser
import kmpworkshop.common.*
import kmpworkshop.common.DefaultApis.callIsDone
import kmpworkshop.common.DefaultApis.callLifetime
import kmpworkshop.common.DefaultApis.queryExceptionThrown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

context(_: CoroutinePuzzleSolutionScope)
suspend fun mapFromLegacyApiWithScaffolding(
    mapFromLegacyApi: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
) {
    withImportantCleanup {
        launch {
            try {
                coroutineScope {
                    mapFromLegacyApi(getUserDatabaseWithLegacyQueryUser(
                        topLevelScope = this@withImportantCleanup,
                    ))
                }
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
