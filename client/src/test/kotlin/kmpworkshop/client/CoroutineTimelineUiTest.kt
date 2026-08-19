@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package kmpworkshop.client

import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kmpworkshop.common.WithCallId
import kmpworkshop.common.CoroutinePuzzleExpectationPayload
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
                            CoroutinePuzzleSolutionResult.CustomFailure("Incomplete call"),
                        )
                    }
                }
            }
        }

        saveScreenshot("coroutine-timeline.png")

        onNodeWithTag("timeline-marker-3-4").performMouseInput { moveTo(center) }
        waitForIdle()
        saveScreenshot("coroutine-timeline-highlighted.png")
        saveScreenshot("coroutine-timeline-tooltip.png", rootIndex = 1)
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
        onNodeWithText("━").assertIsDisplayed()
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

    private fun <T> entry(id: Long, payload: T) = WithCallId(id, payload)
}
