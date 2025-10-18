package kmpworkshop.client

import kmpworkshop.common.UserDatabase

suspend fun maximumAgeFindingWithCoroutines(database: UserDatabase) {
    database.submit(database.queryUser(database.getAllIds().max()).age)
}