package kmpworkshop.client

import kmpworkshop.api.UserDatabaseWithLegacyQueryUser
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.DefaultApis
import kmpworkshop.common.DefaultApis.callIsDone
import kmpworkshop.common.DefaultApis.callLifetime
import kmpworkshop.common.DefaultApis.queryExceptionThrown
import kmpworkshop.common.QueryFetchFailedForSomeReasonException
import kmpworkshop.common.getUserDatabaseWithLegacyQueryUser
import kmpworkshop.common.importantCleanup
import kmpworkshop.common.sideEffect
import kmpworkshop.common.withImportantCleanup
import kmpworkshop.common.submitCall
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
