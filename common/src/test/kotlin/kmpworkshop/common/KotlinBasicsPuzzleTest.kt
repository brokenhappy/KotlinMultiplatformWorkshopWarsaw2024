package kmpworkshop.common

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KotlinBasicsPuzzleTest {
    @Test
    fun `typed solve serializes the solution and preserves a failed attempt`(): Unit = runTest {
        val input = Json.parseToJsonElement("{\"value\": 4}")
        val expected = Json.parseToJsonElement("{\"value\": 8}")
        val puzzle = KotlinBasicsPuzzle { solution ->
            val actual = solution(input)
            KotlinBasicsPuzzleResult.Failed(input, actual, expected)
        }

        val result = puzzle.solve<NumberInput, NumberOutput> { NumberOutput(it.value * 2) }

        assertEquals(
            KotlinBasicsPuzzleResult.Failed(input, Json.parseToJsonElement("{\"value\": 8}"), expected),
            result,
        )
    }

    @Test
    fun `raw solve can be suspend and higher order`(): Unit = runTest {
        var received: JsonElement? = null
        val puzzle = KotlinBasicsPuzzle { solution ->
            received = solution(JsonPrimitive(3))
            KotlinBasicsPuzzleResult.Success
        }

        val result = puzzle.solveRaw { JsonPrimitive(it.jsonPrimitive.content.toInt() + 1) }

        assertIs<KotlinBasicsPuzzleResult.Success>(result)
        assertEquals(JsonPrimitive(4), received)
    }

    @Test
    fun `custom failures are part of the puzzle result domain`() {
        val result = KotlinBasicsPuzzleResult.CustomFailure("invalid answer")

        assertEquals("invalid answer", result.message)
    }

    @Serializable
    private data class NumberInput(val value: Int)

    @Serializable
    private data class NumberOutput(val value: Int)
}
