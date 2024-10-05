package kmpworkshop.client

import kmpworkshop.common.SerializableUser

private fun serializableFindMinimumAgeOf(input: List<SerializableUser>): Int =
    findMinimumAgeOf(input.map { (name, age) -> User(name, age) })

fun checkMinimumAgeSolution() {
    checkCodePuzzle("MinimumAgeFinding.kt", solution = ::serializableFindMinimumAgeOf)
}

private fun serializableFindOldestUserAmong(input: List<SerializableUser>): SerializableUser =
    findOldestUserAmong(input.map { (name, age) -> User(name, age) })
        .let { (name, age) -> SerializableUser(name, age) }

fun checkFindOldestUserSolution() {
    checkCodePuzzle("OldestUserFinding.kt", solution = ::serializableFindOldestUserAmong)
}