package kmpworkshop.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.woutwerkman.calltreevisualizer.gui.CallTreeTheme
import com.woutwerkman.calltreevisualizer.gui.CallTreeUI
import com.woutwerkman.calltreevisualizer.gui.KotlinFqnTextRenderer
import com.woutwerkman.calltreevisualizer.coroutineintegration.CallStackTrackEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select

internal enum class DebuggerCommand { Step, Resume, ResumeUntilNextBatch }

@Composable
internal fun CoroutineDebuggerPanel(
    events: ReceiveChannel<CallStackTrackEvent>?,
    batchBoundaries: ReceiveChannel<Unit>?,
    batchController: CoroutineDebuggerBatchController,
    enabled: Boolean,
) {
    var state by remember(events) { mutableStateOf(CoroutineDebuggerState()) }
    var isPaused by remember(events) { mutableStateOf(false) }
    val controls = remember(events) { Channel<DebuggerCommand>(Channel.UNLIMITED) }

    LaunchedEffect(events) {
        val eventChannel = events ?: return@LaunchedEffect
        // Stepped runs always stop at the first event. Step keeps this flag set so
        // every subsequent event becomes the next stopping point; Resume clears it.
        var pauseBeforeNextEvent = true
        var pauseAtNextBatch = false
        var eventChannelClosed = false
        while (true) {
            select<Unit> {
                eventChannel.onReceiveCatching { result ->
                    val event = result.getOrNull()
                    if (event == null) {
                        eventChannelClosed = true
                    } else {
                        state = state.after(event)
                    }
                    if (event != null && pauseBeforeNextEvent) {
                        isPaused = true
                        when (controls.receive()) {
                            DebuggerCommand.Step -> Unit
                            DebuggerCommand.Resume -> pauseBeforeNextEvent = false
                            DebuggerCommand.ResumeUntilNextBatch -> {
                                pauseBeforeNextEvent = false
                                pauseAtNextBatch = true
                                batchController.resumeUntilNextBatch()
                            }
                        }
                        isPaused = false
                    }
                }
                batchBoundaries?.onReceiveCatching { result ->
                    if (result.getOrNull() != null && pauseAtNextBatch) {
                        pauseAtNextBatch = false
                        isPaused = true
                        when (controls.receive()) {
                            DebuggerCommand.Step -> pauseBeforeNextEvent = true
                            DebuggerCommand.Resume -> Unit
                            DebuggerCommand.ResumeUntilNextBatch -> {
                                pauseAtNextBatch = true
                                batchController.resumeUntilNextBatch()
                            }
                        }
                        isPaused = false
                    }
                }
            }
            if (eventChannelClosed) break
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color(0xFFF7F9FC),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Coroutine debugger", style = MaterialTheme.typography.subtitle1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("debugger-step-button"),
                    enabled = enabled && isPaused,
                    onClick = { controls.trySend(DebuggerCommand.Step) },
                ) { Text("Step") }
                Button(
                    modifier = Modifier.testTag("debugger-resume-button"),
                    enabled = enabled && isPaused,
                    onClick = { controls.trySend(DebuggerCommand.Resume) },
                ) { Text("Resume") }
                Button(
                    modifier = Modifier.testTag("debugger-resume-until-batch-button"),
                    enabled = enabled && isPaused,
                    onClick = { controls.trySend(DebuggerCommand.ResumeUntilNextBatch) },
                ) { Text("Resume until next batch") }
                Text(
                    text = when {
                        !enabled -> "○"
                        isPaused -> "⏸"
                        else -> "●"
                    },
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .testTag("debugger-status-symbol"),
                    color = when {
                        !enabled -> Color(0xFF5F6368)
                        isPaused -> Color(0xFFB3261E)
                        else -> Color(0xFF2E7D32)
                    },
                    style = MaterialTheme.typography.h6,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(Color.White),
            ) {
                if (state.tree == com.woutwerkman.calltreevisualizer.gui.CallTree.Empty) {
                    Text(
                        "The call tree will appear when the solution starts.",
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFF5F6368),
                    )
                } else {
                    CallTreeTheme(darkTheme = false) {
                        CallTreeUI(state.tree, KotlinFqnTextRenderer)
                    }
                }
            }
        }
    }
}
