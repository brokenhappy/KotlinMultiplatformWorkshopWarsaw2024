package kmpworkshop.client

import kmpworkshop.common.SerializableUser

private fun serializableFindMinimumAgeOf(input: List<SerializableUser>): Int =
    findMinimumAgeOf(input.map { (name, age) -> User(name, age) })

fun checkMinimumAgeSolution() {
    checkCodePuzzle("MinimumAgeFinding.kt", solution = ::serializableFindMinimumAgeOf)
}