package kmpworkshop.solutions

import kmpworkshop.api.UserDatabaseWithLegacyQueryUser
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

suspend fun mapFromLegacyApi(database: UserDatabaseWithLegacyQueryUser) {
    coroutineScope {
        database.queryUserWithCallback(
            database.getAllIds().max(),
            onSuccess = {
                this@coroutineScope.launch {
                    database.submit(it.age)
                }
            },
        )
    }
}
