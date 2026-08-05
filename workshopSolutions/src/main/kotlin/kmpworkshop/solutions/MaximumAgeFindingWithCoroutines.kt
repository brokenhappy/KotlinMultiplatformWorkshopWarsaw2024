package kmpworkshop.solutions

import kmpworkshop.api.UserDatabase

suspend fun maximumAgeFindingWithCoroutines(database: UserDatabase) {
    database.submit(database.queryUser(database.getAllIds().max()).age)
}
