@file:Suppress("FunctionName")
package kmpworkshop.server

import androidx.compose.foundation.gestures.Orientation.Vertical
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight.Companion.Bold
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun ServerUi() {
    val state by produceState(initialValue = ServerState(emptyList(), emptyList(), WorkshopStage.Registration)) {
        serverState().collect { value = it }
    }
    val scope = rememberCoroutineScope()

    ServerUi(state, onStateChange = { stateUpdater -> scope.launch { updateServerState(stateUpdater) } })
}

@Composable
private fun ServerUi(state: ServerState, onStateChange: ((ServerState) -> ServerState) -> Unit) {
    Column {
        StageTopBar(state.currentStage, onStageChange = { newStage -> onStateChange { it.copy(currentStage = newStage) } })
        when (state.currentStage) {
            WorkshopStage.Registration -> Registration(state, onStateChange)
            WorkshopStage.PalindromeCheckTask -> PalindromeCheckTask()
        }
    }
}

@Composable
private fun PalindromeCheckTask() {

}

@Composable
private fun StageTopBar(stage: WorkshopStage, onStageChange: (WorkshopStage) -> Unit) {
    Row(modifier = Modifier.padding(16.dp)) {
        Row {
            MoveStageButton(stage, onStageChange, -1, Key.DirectionLeft) {
                Text("<")
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                var expanded by remember { mutableStateOf(false) }
                ClickableText(
                    text = AnnotatedString("Go to file: ${stage.kotlinFile}"),
                    style = TextStyle(fontSize = 20.sp, fontWeight = Bold),
                    onClick = { expanded = true }
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    WorkshopStage.entries.forEach {
                        DropdownMenuItem(onClick = { expanded = false; onStageChange(it) }) {
                            Text(it.kotlinFile)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            MoveStageButton(stage, onStageChange, 1, Key.DirectionRight) {
                Text(">")
            }
        }
    }
    Divider(
        modifier = Modifier.padding(bottom = 8.dp),
        color = Color.Black,
        thickness = 1.dp,
    )
}

@Composable
private fun MoveStageButton(
    stage: WorkshopStage,
    onStageChange: (WorkshopStage) -> Unit,
    offset: Int,
    key: Key, // TODO: Make work?
    content: @Composable RowScope.() -> Unit
) {
    Button(
        enabled = stage.moving(offset) != null,
        onClick = { onStageChange(stage.moving(offset)!!) },
        content = content,
    )
}

private fun WorkshopStage.moving(offset: Int): WorkshopStage? =
    WorkshopStage.entries.getOrNull(ordinal + offset)

@Composable
private fun Registration(
    state: ServerState,
    onStateChange: ((ServerState) -> ServerState) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp).scrollable(rememberScrollState(), orientation = Vertical)) {
        BasicText(text = "Number of verified participants: ${state.participants.size}")
        state.participants.forEach { participant ->
            Row(modifier = Modifier.padding(8.dp)) {
                BasicText(text = participant.name)
                BasicText(
                    text = "Verified",
                    modifier = Modifier.padding(start = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onStateChange { it.copy(participants = it.participants - participant) } }) {
                    Text("Delete")
                }
            }
        }
        state.unverifiedParticipants.forEach { participant ->
            Row(modifier = Modifier.padding(8.dp)) {
                BasicText(text = participant.name)
                BasicText(
                    text = "Pending",
                    modifier = Modifier.padding(start = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onStateChange { it.copy(unverifiedParticipants = it.unverifiedParticipants - participant) } }) {
                    Text("Reject")
                }
            }
        }
    }
}