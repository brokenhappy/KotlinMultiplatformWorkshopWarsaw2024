package kmpworkshop.solutions

import kmpworkshop.api.UserDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

suspend fun maximumAgeFindingWithCoroutines(database: UserDatabase) {
    database.submit(
        coroutineScope {
            database
                .getAllIds()
                .map { async { database.queryUser(it).age } }
                .awaitAll()
                .max()
        },
    )
}
