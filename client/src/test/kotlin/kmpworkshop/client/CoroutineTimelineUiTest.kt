@file:OptIn(ExperimentalTestApi::class)

package kmpworkshop.client

import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleCallPayload.Outcome
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
    fun `a callback invocation displays the emitted value`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Surface(Modifier.size(900.dp, 560.dp)) {
                    context(testMetadata) { CoroutineTimelineWithMetadata(flowHistory(), null) }
                }
            }
        }
        onNodeWithTag("timeline-marker-1-3").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("Emitting value").assertIsDisplayed()
        onNodeWithText("Value: 8").assertIsDisplayed()
    }

    @Test
    fun `timeline initially follows the newest calls and exchanges`() = runComposeUiTest {
        val longHistory = (1L..18L).mapIndexed { index, id ->
            CoroutinePuzzleHistoryBatch.Submission(listOf(entry(
                id,
                CoroutinePuzzleCallPayload.CallSubmitted(
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
                                        expectedCallbackCompletionOfCallId = -2,
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
        onNodeWithText("Expected callback to finish").assertIsDisplayed()
        onNodeWithText("The callback has not finished its work and structured children.").assertIsDisplayed()
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
                                        expectedCallbackCompletionOfCallId = -2,
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
        onNodeWithText("Expected callback to finish").assertIsDisplayed()
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
                                    CoroutinePuzzleExpectedFollowup(UiTestApis.numbers.id, expectedCallbackCompletionOfCallId = -2),
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
        onNodeWithText("Expected callback to finish").assertIsDisplayed()
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
        onAllNodesWithText("↗").assertCountEquals(2)
    }

    private fun ComposeUiTest.saveScreenshot(name: String, rootIndex: Int = 0) {
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
                entry(1, CoroutinePuzzleCallPayload.CallSubmitted(load, JsonPrimitive(42))),
                entry(2, CoroutinePuzzleCallPayload.CallSubmitted(save, JsonObject(emptyMap()))),
            )),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(entry(1, Outcome.Returned(JsonPrimitive("Ada"))))),
            CoroutinePuzzleHistoryBatch.Submission(listOf(
                entry(3, CoroutinePuzzleCallPayload.CallSubmitted(hanging, JsonObject(emptyMap()))),
            )),
            CoroutinePuzzleHistoryBatch.Submission(listOf(entry(2, CoroutinePuzzleCallPayload.Cancel))),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(entry(2, Outcome.Cancelled))),
            CoroutinePuzzleHistoryBatch.Submission(listOf(entry(2, Outcome.Cancelled))),
        )
    }

    private fun flowHistory(): List<CoroutinePuzzleHistoryBatch> = listOf(
        CoroutinePuzzleHistoryBatch.Submission(listOf(
            entry(1, CoroutinePuzzleCallPayload.HigherOrderCallSubmitted(UiTestApis.numbers.id, collectorArgument(1))),
        )),
        CoroutinePuzzleHistoryBatch.Expectation(listOf(
            entry(-1, CoroutinePuzzleCallPayload.CallbackInvoked(1, JsonPrimitive(7))),
        )),
        CoroutinePuzzleHistoryBatch.Submission(listOf(
            entry(-1, Outcome.Returned(JsonObject(emptyMap()))),
            entry(2, CoroutinePuzzleCallPayload.CallSubmitted(UiTestApis.callNumber.id, JsonPrimitive(7))),
        )),
        CoroutinePuzzleHistoryBatch.Expectation(listOf(
            entry(-2, CoroutinePuzzleCallPayload.CallbackInvoked(1, JsonPrimitive(8))),
        )),
    )

    private fun twoCollectorFlowHistory(): List<CoroutinePuzzleHistoryBatch> = listOf(
        CoroutinePuzzleHistoryBatch.Submission(listOf(
            entry(1, CoroutinePuzzleCallPayload.HigherOrderCallSubmitted(UiTestApis.numbers.id, collectorArgument(1))),
            entry(2, CoroutinePuzzleCallPayload.HigherOrderCallSubmitted(UiTestApis.numbers.id, collectorArgument(2))),
        )),
        CoroutinePuzzleHistoryBatch.Expectation(listOf(
            entry(-1, CoroutinePuzzleCallPayload.CallbackInvoked(1, JsonPrimitive(7))),
            entry(-2, CoroutinePuzzleCallPayload.CallbackInvoked(2, JsonPrimitive(8))),
        )),
        CoroutinePuzzleHistoryBatch.Submission(listOf(
            entry(-1, Outcome.Returned(JsonObject(emptyMap()))),
        )),
    )

    private fun collectorArgument(id: Long) = JsonObject(mapOf(
        "first" to JsonObject(emptyMap()),
        "second" to JsonPrimitive(id),
    ))

    private fun <T> entry(id: Long, payload: T) = WithCallId(id, payload)
}
