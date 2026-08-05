package kmpworkshop.common

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/**
 * The channel-level protocol used by Kotlin basics puzzles.
 *
 * Keeping this separate from the RPC representation makes local puzzle implementations and transports use the same
 * lifecycle, and gives tests a seam at which alternate implementations can be substituted.
 */
typealias KotlinBasicsPuzzleProtocol = CommunicationProtocol<SolvingStatus, JsonElement>

fun Resource<KotlinBasicsPuzzleProtocol>.asKotlinBasicsPuzzle(): KotlinBasicsPuzzle = KotlinBasicsPuzzle { solution ->
    use { (questions, answers) ->
        try {
            for (message in questions) {
                when (message) {
                    is SolvingStatus.Next -> answers.send(solution(message.questionJson))
                    is SolvingStatus.Done -> return@use message.result
                }
            }
            KotlinBasicsPuzzleResult.CustomFailure("The puzzle communication ended before it completed.")
        } catch (_: SerializationException) {
            KotlinBasicsPuzzleResult.CustomFailure(accidentalChangesMadeMessage)
        } finally {
            answers.close()
        }
    }
}
