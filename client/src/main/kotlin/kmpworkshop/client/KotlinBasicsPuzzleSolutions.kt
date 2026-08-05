package kmpworkshop.client

import kmpworkshop.common.SerializableUser

data class KotlinBasicsPuzzleSolutions(
    val palindromeCheckSolution: suspend (String) -> Boolean,
    val minimumAgeSolution: suspend (List<SerializableUser>) -> Int,
    val oldestUserSolution: suspend (List<SerializableUser>) -> SerializableUser,
)
