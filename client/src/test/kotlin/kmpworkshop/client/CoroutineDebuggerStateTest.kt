package kmpworkshop.client

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.woutwerkman.calltreevisualizer.coroutineintegration.CallStackTrackEvent
import com.woutwerkman.calltreevisualizer.coroutineintegration.CallStackTrackEventType
import com.woutwerkman.calltreevisualizer.coroutineintegration.CallTreeEventNode
import com.woutwerkman.calltreevisualizer.gui.CallStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class CoroutineDebuggerStateTest {
    @Test
    fun `uses the published call tree reducer for nested calls and exceptions`() {
        val root = CallTreeEventNode(1, "solution", null, false)
        val child = CallTreeEventNode(2, "solution.child", root, false)
        val state = CoroutineDebuggerState()
            .after(CallStackTrackEvent(root, CallStackTrackEventType.CallStackPushType))
            .after(CallStackTrackEvent(child, CallStackTrackEventType.CallStackPushType))
            .after(CallStackTrackEvent(child, CallStackTrackEventType.CallStackThrowType(IllegalStateException("boom"))))

        val childNode = state.tree.roots
            .single { it.id == root.id }
            .children
            .single { it.id == child.id }
        assertEquals(CallStatus.Failed, childNode.status)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `debugger exposes only step and resume controls`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CoroutineDebuggerPanel(
                    events = null,
                    batchBoundaries = null,
                    batchController = CoroutineDebuggerBatchController(),
                    enabled = true,
                )
            }
        }

        onNodeWithTag("debugger-step-button").assertIsDisplayed()
        onNodeWithTag("debugger-resume-button").assertIsDisplayed()
        onNodeWithTag("debugger-resume-until-batch-button").assertIsDisplayed()
        onAllNodesWithTag("debugger-status-symbol").assertCountEquals(0)
        onAllNodesWithTag("debugger-pause-button").assertCountEquals(0)
    }
}
