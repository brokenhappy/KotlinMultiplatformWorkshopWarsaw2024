package kmpworkshop.client

import kmpworkshop.common.SerializableUser
import kmpworkshop.api.User
import kmpworkshop.solutions.findMinimumAgeOf
import kmpworkshop.solutions.findOldestUserAmong

fun serializableFindMinimumAgeOf(input: List<SerializableUser>): Int =
    findMinimumAgeOf(input.map { (name, age) -> User(name, age) })

fun serializableFindOldestUserAmong(input: List<SerializableUser>): SerializableUser =
    findOldestUserAmong(input.map { (name, age) -> User(name, age) })
        .let { (name, age) -> SerializableUser(name, age) }
