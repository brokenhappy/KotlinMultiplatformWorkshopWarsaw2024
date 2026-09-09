package com.kotlinworkshop.test

import kmpworkshop.client.clientMetadataOf
import kmpworkshop.client.toMessage
import kmpworkshop.common.EndpointDescriptorRegistry
import kmpworkshop.common.descriptor
import kmpworkshop.common.higherOrderDescriptor
import kmpworkshop.common.submitCall
import kmpworkshop.common.asPuzzle
import kmpworkshop.common.solve
import kmpworkshop.common.CoroutinePuzzleProtocol
import kmpworkshop.common.Resource
import kmpworkshop.common.ApiKey
import kmpworkshop.common.CoroutinePuzzleExpectationBatchOrCompletion
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage.SumOfTwoIntsSlow
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleEndPointId
import kmpworkshop.common.MetadataNotFoundException
import kmpworkshop.server.expectCall
import kmpworkshop.server.serverMetadataOf
import kmpworkshop.server.coroutinePuzzleWithMetadata
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testWorkshopService
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private object MetadataTestApis : EndpointDescriptorRegistry() {
    val work by descriptor<Unit, Unit>("Call test work()")

    init {
        seal()
    }
}

private val testServerMetadata = serverMetadataOf(MetadataTestApis) {
    MetadataTestApis.work.register(actionDescriptionInErrors = "Call the test work endpoint")
}

private val testClientMetadata = clientMetadataOf(MetadataTestApis) {
    MetadataTestApis.work.register(onStartDescription = { "Starting test work" })
}

class CoroutinePuzzleMetadataTest {
    @Test
    fun `endpoint hashes include callback input and output types`() {
        val original = object : EndpointDescriptorRegistry() {
            @Suppress("unused")
            val callback by higherOrderDescriptor<Unit, Int, String, Unit>("callback")
            init { seal() }
        }
        val changedInput = object : EndpointDescriptorRegistry() {
            @Suppress("unused")
            val callback by higherOrderDescriptor<Unit, Long, String, Unit>("callback")
            init { seal() }
        }
        val changedOutput = object : EndpointDescriptorRegistry() {
            @Suppress("unused")
            val callback by higherOrderDescriptor<Unit, Int, Boolean, Unit>("callback")
            init { seal() }
        }
        assertEquals(3, listOf(original, changedInput, changedOutput).map { it.endpointHash() }.distinct().size)
    }

    @Test
    fun `test api uses server and client metadata blocks`() = runTest {
        val puzzle: Resource<CoroutinePuzzleProtocol> = context(testServerMetadata) {
            coroutinePuzzleWithMetadata {
                MetadataTestApis.work.expectCall<Unit, Unit> { }
            }
        }
        val result = puzzle.asPuzzle().solve {
            MetadataTestApis.work.submitCall<Unit, Unit>(Unit)
        }

        assertContains(context(testClientMetadata) { result.toMessage() }, "The puzzle was solved")
        assertContains(testClientMetadata.descriptionFor(MetadataTestApis.work.id), "Call test work")
    }

    @Test
    fun `incorrect client metadata hash is rejected before puzzle startup`() = runTest {
        testWorkshopService(serverStateThatOpened(SumOfTwoIntsSlow)).use { (service) ->
            val completions = service.doCoroutinePuzzleSolveAttempt(
                testParticipant.apiKey,
                SumOfTwoIntsSlow.name,
                "incorrect-hash",
                emptyFlow(),
            ).toList()

            assertEquals(
                listOf(
                    CoroutinePuzzleExpectationBatchOrCompletion.Completion(
                        CoroutinePuzzleSolutionResult.CustomFailure(
                            "Client and server coroutine puzzle APIs do not match.",
                        ),
                    ),
                ),
                completions,
            )
        }
    }

    @Test
    fun `missing endpoint metadata is rejected`() {
        assertFailsWith<MetadataNotFoundException> {
            testClientMetadata.descriptionFor(CoroutinePuzzleEndPointId("missing"))
        }
    }
}
