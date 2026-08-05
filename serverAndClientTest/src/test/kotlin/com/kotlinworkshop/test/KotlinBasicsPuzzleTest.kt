package com.kotlinworkshop.test

import kmpworkshop.common.ApiKey
import kmpworkshop.common.KotlinBasicsPuzzleResult
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage
import kmpworkshop.common.asServer
import kmpworkshop.common.accidentalChangesMadeMessage
import kmpworkshop.common.solve
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import testWorkshopService
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KotlinBasicsPuzzleServiceTest {
    @Test
    fun `provider maps a successful server attempt to domain success`(): Unit = runBlocking {
        testWorkshopService(serverStateThatOpened(KotlinBasicsPuzzleStage.PalindromeCheckTask)).use { (service) ->
            val result = service.asServer(ApiKey("1234-5678"))
                .kotlinBasicsPuzzle(KotlinBasicsPuzzleStage.PalindromeCheckTask)
                .solve<String, Boolean> { it == it.reversed() }

            assertIs<KotlinBasicsPuzzleResult.Success>(result)
        }
    }

    @Test
    fun `provider maps a wrong answer to domain failure`(): Unit = runBlocking {
        testWorkshopService(serverStateThatOpened(KotlinBasicsPuzzleStage.PalindromeCheckTask)).use { (service) ->
            val result = service.asServer(ApiKey("1234-5678"))
                .kotlinBasicsPuzzle(KotlinBasicsPuzzleStage.PalindromeCheckTask)
                .solve<String, Boolean> { false }

            val failure = assertIs<KotlinBasicsPuzzleResult.Failed>(result)
            assertEquals(JsonPrimitive("racecar"), failure.input)
            assertEquals(JsonPrimitive(false), failure.actual)
            assertEquals(JsonPrimitive(true), failure.expected)
        }
    }

    @Test
    fun `a throwing solution remains a caller failure`(): Unit = runBlocking {
        testWorkshopService(serverStateThatOpened(KotlinBasicsPuzzleStage.PalindromeCheckTask)).use { (service) ->
            val exception = kotlin.test.assertFailsWith<IllegalStateException> {
                service.asServer(ApiKey("1234-5678"))
                    .kotlinBasicsPuzzle(KotlinBasicsPuzzleStage.PalindromeCheckTask)
                    .solve<String, Boolean> { error("solution exploded") }
            }

            assertEquals("solution exploded", exception.message)
        }
    }

    @Test
    fun `a serialization failure becomes a custom puzzle failure`(): Unit = runBlocking {
        testWorkshopService(serverStateThatOpened(KotlinBasicsPuzzleStage.PalindromeCheckTask)).use { (service) ->
            val result = service.asServer(ApiKey("1234-5678"))
                .kotlinBasicsPuzzle(KotlinBasicsPuzzleStage.PalindromeCheckTask)
                .solveRaw { throw SerializationException("answer cannot be serialized") }

            val failure = assertIs<KotlinBasicsPuzzleResult.CustomFailure>(result)
            assertEquals(accidentalChangesMadeMessage, failure.message)
        }
    }
}
