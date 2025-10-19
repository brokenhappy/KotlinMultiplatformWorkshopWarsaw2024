package kmpworkshop.client

import kmpworkshop.common.UserDatabaseWithLegacyQueryUser
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

suspend fun mapFromLegacyApi(database: UserDatabaseWithLegacyQueryUser) {
    database.queryUserWithCallback(
        database.getAllIds().max(),
        onSuccess = {
            GlobalScope.launch {
                database.submit(it.age)
            }
        },
    )
}