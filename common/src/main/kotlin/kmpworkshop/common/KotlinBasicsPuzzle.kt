package kmpworkshop.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A cold puzzle for the Kotlin basics lessons.
 *
 * Each invocation represents one independent attempt. The implementation owns how inputs are supplied and how the
 * attempt is evaluated; callers provide only the raw solution function. JSON is the deliberately small boundary that
 * keeps this abstraction independent of the input and output domain types. Intermediate input requests stay inside the
 * implementation; the caller receives a result describing whether the attempt succeeded or supplied a wrong answer.
 */
fun interface KotlinBasicsPuzzle {
    suspend fun solveRaw(solution: suspend (JsonElement) -> JsonElement): KotlinBasicsPuzzleResult
}

@Serializable
sealed interface KotlinBasicsPuzzleResult {
    @Serializable
    data object Success : KotlinBasicsPuzzleResult

    @Serializable
    data class Failed(
        val input: JsonElement,
        val actual: JsonElement,
        val expected: JsonElement,
    ) : KotlinBasicsPuzzleResult

    @Serializable
    data class CustomFailure(val message: String) : KotlinBasicsPuzzleResult
}

suspend inline fun <reified Input, reified Output> KotlinBasicsPuzzle.solve(
    noinline solution: suspend (Input) -> Output,
): KotlinBasicsPuzzleResult =
    solveRaw { input -> Json.encodeToJsonElement<Output>(solution(Json.decodeFromJsonElement<Input>(input))) }
