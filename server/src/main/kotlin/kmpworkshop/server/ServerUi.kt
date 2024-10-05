@file:Suppress("FunctionName")
package kmpworkshop.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Button
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ServerUi() {
    val state by produceState(initialValue = ServerState(emptyList(), emptyList())) {
        serverState().collect { value = it }
    }
    val scope = rememberCoroutineScope()

    ServerUi(state, onStateChange = { stateUpdater -> scope.launch { updateServerState(stateUpdater) } })
}

@Composable
private fun ServerUi(state: ServerState, onStateChange: ((ServerState) -> ServerState) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
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