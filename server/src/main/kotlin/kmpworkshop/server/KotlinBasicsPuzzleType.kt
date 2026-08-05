package kmpworkshop.server

import kmpworkshop.common.KotlinBasicsPuzzleProtocol
import kmpworkshop.common.KotlinBasicsPuzzleResult
import kmpworkshop.common.Resource
import kmpworkshop.common.SerializableUser
import kmpworkshop.common.SolvingStatus
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage
import kmpworkshop.common.communicationProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement


object KotlinBasicsPuzzleType : PuzzleType<KotlinBasicsPuzzleStage, SolvingStatus, JsonElement> {
    override fun enumEntries(): List<KotlinBasicsPuzzleStage> = kotlin.enums.enumEntries()
    override fun customError(message: String): SolvingStatus =
        SolvingStatus.Done(KotlinBasicsPuzzleResult.CustomFailure(message))

    override fun isSuccessfulCompletion(outgoing: SolvingStatus): Boolean =
        outgoing is SolvingStatus.Done && outgoing.result is KotlinBasicsPuzzleResult.Success

    override fun findPuzzleFor(stage: KotlinBasicsPuzzleStage): Resource<KotlinBasicsPuzzleProtocol> = when (stage) {
        KotlinBasicsPuzzleStage.PalindromeCheckTask -> kotlinBasicsPuzzleWithFixedInOut(
            "racecar" to true,
            "Racecar" to false,
            "radar" to true,
            "foo" to false,
            "abba" to true,
            "ABBA" to true,
        )
        KotlinBasicsPuzzleStage.FindMinimumAgeOfUserTask -> kotlinBasicsPuzzleWithFixedInOut(
            listOf(SerializableUser("John", 18)) to 18,
            listOf(SerializableUser("John", 0)) to 0,
            listOf(
                SerializableUser("John", 0),
                SerializableUser("Jane", 10),
            ) to 0,
            listOf(
                SerializableUser("John", 10),
                SerializableUser("Jane", 100),
            ) to 10,
            listOf(
                SerializableUser("John", 100),
                SerializableUser("Jane", 10),
            ) to 10,
        )
        KotlinBasicsPuzzleStage.FindOldestUserTask -> kotlinBasicsPuzzleWithFixedInOut(
            listOf(SerializableUser("John", 18)) to SerializableUser("John", 18),
            listOf(SerializableUser("John", 0)) to SerializableUser("John", 0),
            listOf(
                SerializableUser("John", 0),
                SerializableUser("Jane", 10),
            ) to SerializableUser("Jane", 10),
            listOf(
                SerializableUser("John", 10),
                SerializableUser("Jane", 100),
            ) to SerializableUser("Jane", 100),
            listOf(
                SerializableUser("John", 100),
                SerializableUser("Jane", 10),
            ) to SerializableUser("John", 100),
        )
    }
}

private inline fun <reified T, reified R> kotlinBasicsPuzzleWithFixedInOut(
    vararg inAndOutputs: Pair<T, R>
): Resource<KotlinBasicsPuzzleProtocol> = communicationProtocol { outgoing, incoming ->
    try {
        for ((input, expectedOutput) in inAndOutputs) {
            val inputJson = Json.encodeToJsonElement<T>(input)
            outgoing.send(SolvingStatus.Next(inputJson))
            val actual = incoming.receiveCatching().getOrNull() ?: return@communicationProtocol
            val expected = Json.encodeToJsonElement<R>(expectedOutput)
            if (actual != expected) {
                outgoing.send(SolvingStatus.Done(KotlinBasicsPuzzleResult.Failed(inputJson, actual, expected)))
                return@communicationProtocol
            }
        }
        outgoing.send(SolvingStatus.Done(KotlinBasicsPuzzleResult.Success))
    } finally {
        outgoing.close()
    }
}
