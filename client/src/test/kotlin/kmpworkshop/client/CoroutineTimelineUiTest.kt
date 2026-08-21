@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package kmpworkshop.client

import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kmpworkshop.common.WithCallId
import kmpworkshop.common.CoroutinePuzzleExpectationPayload
import kmpworkshop.common.CoroutinePuzzleExpectedFollowup
import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.CoroutinePuzzleHistoryBatch
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.EndpointDescriptorRegistry
import kmpworkshop.common.descriptor
import kmpworkshop.common.flowDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

private object UiTestApis : EndpointDescriptorRegistry() {
    val loadParticipant by descriptor<Int, String>("load the current workshop participant")
    val saveScore by descriptor<Unit, Unit>("save the calculated workshop score")
    val waitForUpdate by descriptor<Unit, Unit>("wait for the next participant update")
    val callNumber by descriptor<Int, Unit>("call number")
    val numbers by flowDescriptor<Unit, Int>("numbers")

    init { seal() }
}

private val testMetadata = clientMetadataOf(UiTestApis) {
    UiTestApis.numbers.register(isFlowEndpoint = true)
}

class CoroutineTimelineUiTest {
    @Test
    fun `renders polished completed and incomplete call timeline`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(900.dp, 560.dp)) {
                    context(testMetadata) {
                        CoroutineTimelineWithMetadata(
                            sampleHistory(),
                            CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure(
                                expectedFollowups = listOf(CoroutinePuzzleExpectedFollowup(UiTestApis.callNumber.id)),
                            ),
                        )
                    }
                }
            }
        }

        saveScreenshot("coroutine-timeline.png")

        onNodeWithText("Expected").assertIsDisplayed()
        onAllNodesWithText("…").assertCountEquals(0)
        onAllNodesWithText("?").assertCountEquals(0)
        onNodeWithTag("timeline-expected-marker-0").assertIsDisplayed()
        onNodeWithTag("timeline-marker-3-2").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("Started").assertIsDisplayed()
        saveScreenshot("coroutine-timeline-highlighted.png")
        saveScreenshot("coroutine-timeline-tooltip.png", rootIndex = 1)
    }

    @Test
    fun `only an identified incorrect request is red`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(900.dp, 560.dp)) {
                    context(testMetadata) {
                        CoroutineTimelineWithMetadata(
                            sampleHistory(),
                            CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure(
                                submissions = listOf(UiTestApis.waitForUpdate.id),
                                expectations = emptyList(),
                                incorrectSubmissions = listOf(UiTestApis.waitForUpdate.id),
                            ),
                        )
                    }
                }
            }
        }

        onNodeWithTag("timeline-marker-3-2").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("Unexpected function call").assertIsDisplayed()
        onNodeWithText("This function call is not currently expected.").assertIsDisplayed()
    }

    @Test
    fun `an unmatched request with no matching expectation is red`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(900.dp, 560.dp)) {
                    context(testMetadata) {
                        CoroutineTimelineWithMetadata(
                            sampleHistory(),
                            CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
                                unexpectedSubmissions = listOf(UiTestApis.waitForUpdate.id),
                                expectations = listOf(CoroutinePuzzleExpectedFollowup(UiTestApis.numbers.id)),
                            ),
                        )
                    }
                }
            }
        }

        onNodeWithTag("timeline-marker-3-2").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("Unexpected function call").assertIsDisplayed()
    }

    @Test
    fun `an unexpected flow start has a flow-specific tooltip`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(900.dp, 560.dp)) {
                    context(testMetadata) {
                        CoroutineTimelineWithMetadata(
                            history = flowHistory().take(1),
                            result = CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
                                unexpectedSubmissions = listOf(UiTestApis.numbers.id),
                                expectations = emptyList(),
                            ),
                        )
                    }
                }
            }
        }

        onNodeWithTag("timeline-marker-1-0").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("Unexpected flow start").assertIsDisplayed()
        onNodeWithText("Starting this flow is not currently expected.").assertIsDisplayed()
    }

    @Test
    fun `an unexpected flow emission request has an emission-specific tooltip`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(900.dp, 560.dp)) {
                    context(testMetadata) {
                        CoroutineTimelineWithMetadata(
                            history = flowHistory(),
                            result = CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
                                unexpectedSubmissions = listOf(UiTestApis.numbers.id),
                                expectations = emptyList(),
                            ),
                        )
                    }
                }
            }
        }

        onNodeWithTag("timeline-marker-1-3").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("Unexpected emission request").assertIsDisplayed()
        onNodeWithText("Requesting the next element from this flow is not currently expected.").assertIsDisplayed()
    }

    @Test
    fun `timeline initially follows the newest calls and exchanges`() = runComposeUiTest {
        val longHistory = (1L..18L).mapIndexed { index, id ->
            CoroutinePuzzleHistoryBatch.Submission(listOf(entry(
                id,
                CoroutinePuzzleSubmissionPayload.CallSubmitted(
                    UiTestApis.callNumber.id,
                    JsonPrimitive(index),
                ),
            )))
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.size(700.dp, 420.dp)) {
                    context(testMetadata) { CoroutineTimelineWithMetadata(longHistory, null) }
                }
            }
        }

        waitForIdle()
        onNodeWithTag("timeline-marker-18-17").assertIsDisplayed()
        onNodeWithTag("timeline-marker-1-0").assertIsNotDisplayed()
    }

    @Test
    fun `shows open expected calls when the solution made no calls`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(700.dp, 420.dp)) {
                    context(testMetadata) {
                        CoroutineTimelineWithMetadata(
                            history = emptyList(),
                            result = CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure(
                                expectedFollowups = listOf(CoroutinePuzzleExpectedFollowup(UiTestApis.callNumber.id)),
                            ),
                        )
                    }
                }
            }
        }

        onNodeWithText("Expected: call number").assertIsDisplayed()
        onNodeWithTag("timeline-expected-marker-0").assertIsDisplayed()
    }

    @Test
    fun `aligns an expected flow emission with its collector ID`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(700.dp, 420.dp)) {
                    context(testMetadata) {
                        CoroutineTimelineWithMetadata(
                            history = flowHistory(),
                            result = CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure(
                                expectedFollowups = listOf(
                                    CoroutinePuzzleExpectedFollowup(
                                        UiTestApis.numbers.id,
                                        collectorArgument(42),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        onNodeWithTag("timeline-expected-marker-1").assertIsDisplayed()
        onAllNodesWithTag("timeline-expected-marker-0").assertCountEquals(0)
        onNodeWithTag("timeline-expected-marker-1").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("Expected request for a new emission").assertIsDisplayed()
        onNodeWithText("The puzzle expected the next element from this flow to be requested. But that request was never made.").assertIsDisplayed()
    }

    @Test
    fun `aligns an expected cancellation with the call that should be cancelled`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(700.dp, 420.dp)) {
                    context(testMetadata) {
                        CoroutineTimelineWithMetadata(
                            history = sampleHistory(),
                            result = CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
                                unexpectedSubmissions = listOf(UiTestApis.callNumber.id),
                                expectations = listOf(
                                    CoroutinePuzzleExpectedFollowup(
                                        endPoint = UiTestApis.waitForUpdate.id,
                                        expectedCancellationOfCallId = 3,
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        onAllNodesWithTag("timeline-expected-marker-0").assertCountEquals(0)
        onNodeWithTag("timeline-expected-marker-3").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("Expected cancellation request").assertIsDisplayed()
        onNodeWithText("The puzzle expected this call to be cancelled. But the cancellation request was never made.").assertIsDisplayed()
    }

    @Test
    fun `aligns an expected emission with its collector when the same flow has two collectors`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(700.dp, 420.dp)) {
                    context(testMetadata) {
                        CoroutineTimelineWithMetadata(
                            history = twoCollectorFlowHistory(),
                            result = CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure(
                                expectedFollowups = listOf(
                                    CoroutinePuzzleExpectedFollowup(
                                        UiTestApis.numbers.id,
                                        collectorArgument(43),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        onAllNodesWithTag("timeline-expected-marker-0").assertCountEquals(0)
        onNodeWithTag("timeline-expected-marker-2").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("Expected request for a new emission").assertIsDisplayed()
    }

    @Test
    fun `aligns an expected flow emission after an unexpected submission`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(700.dp, 420.dp)) {
                    context(testMetadata) {
                        CoroutineTimelineWithMetadata(
                            history = flowHistory(),
                            result = CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
                                unexpectedSubmissions = listOf(UiTestApis.callNumber.id),
                                expectations = listOf(
                                    CoroutinePuzzleExpectedFollowup(UiTestApis.numbers.id, collectorArgument(42)),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        onAllNodesWithTag("timeline-expected-marker-0").assertCountEquals(0)
        onNodeWithTag("timeline-expected-marker-1").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("Expected request for a new emission").assertIsDisplayed()
    }

    @Test
    fun `shows when a flow collector requests its next element`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(700.dp, 420.dp)) {
                    context(testMetadata) { CoroutineTimelineWithMetadata(flowHistory(), null) }
                }
            }
        }

        onNodeWithTag("timeline-marker-1-0").assertIsDisplayed()
        onNodeWithText("↗").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.saveScreenshot(name: String, rootIndex: Int = 0) {
        val image = onAllNodes(isRoot())[rootIndex].captureToImage()
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val file = File("build/reports/ui-tests/$name")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }

    private fun sampleHistory(): List<CoroutinePuzzleHistoryBatch> {
        val load = UiTestApis.loadParticipant.id
        val save = UiTestApis.saveScore.id
        val hanging = UiTestApis.waitForUpdate.id
        return listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                entry(1, CoroutinePuzzleSubmissionPayload.CallSubmitted(load, JsonPrimitive(42))),
                entry(2, CoroutinePuzzleSubmissionPayload.CallSubmitted(save, JsonObject(emptyMap()))),
            )),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(entry(1, CoroutinePuzzleExpectationPayload.CallAnswered(JsonPrimitive("Ada"))))),
            CoroutinePuzzleHistoryBatch.Submission(listOf(entry(3, CoroutinePuzzleSubmissionPayload.CallSubmitted(hanging, JsonObject(emptyMap()))))),
            CoroutinePuzzleHistoryBatch.Submission(listOf(entry(2, CoroutinePuzzleSubmissionPayload.CallShouldCancel))),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(entry(2, CoroutinePuzzleExpectationPayload.CallCancellationCompleted))),
        )
    }

    private fun flowHistory(): List<CoroutinePuzzleHistoryBatch> {
        val collectorArgument = JsonObject(mapOf(
            "callId" to JsonPrimitive(42),
            "payload" to JsonObject(emptyMap()),
        ))
        return listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                entry(1, CoroutinePuzzleSubmissionPayload.CallSubmitted(UiTestApis.numbers.id, collectorArgument)),
            )),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(
                entry(1, CoroutinePuzzleExpectationPayload.CallAnswered(JsonPrimitive(7))),
            )),
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                entry(2, CoroutinePuzzleSubmissionPayload.CallSubmitted(UiTestApis.callNumber.id, JsonPrimitive(7))),
            )),
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                entry(3, CoroutinePuzzleSubmissionPayload.CallSubmitted(UiTestApis.numbers.id, collectorArgument)),
            )),
        )
    }

    private fun twoCollectorFlowHistory(): List<CoroutinePuzzleHistoryBatch> = listOf(
        CoroutinePuzzleHistoryBatch.Submission(listOf(
            entry(1, CoroutinePuzzleSubmissionPayload.CallSubmitted(UiTestApis.numbers.id, collectorArgument(42))),
            entry(2, CoroutinePuzzleSubmissionPayload.CallSubmitted(UiTestApis.numbers.id, collectorArgument(43))),
        )),
        CoroutinePuzzleHistoryBatch.Expectation(listOf(
            entry(1, CoroutinePuzzleExpectationPayload.CallAnswered(JsonPrimitive(7))),
            entry(2, CoroutinePuzzleExpectationPayload.CallAnswered(JsonPrimitive(8))),
        )),
    )

    private fun collectorArgument(id: Long) = JsonObject(mapOf(
        "callId" to JsonPrimitive(id),
        "payload" to JsonObject(emptyMap()),
    ))

    private fun <T> entry(id: Long, payload: T) = WithCallId(id, payload)
}
