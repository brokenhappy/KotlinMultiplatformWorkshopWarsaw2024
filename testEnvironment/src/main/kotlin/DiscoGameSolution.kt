import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kmpworkshop.client.toComposeColor
import kmpworkshop.common.DiscoGameInstruction
import kmpworkshop.common.WorkshopServer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

@Composable
fun DiscoGameSolution(server: WorkshopServer) {
    Column {
        var toggle by remember { mutableStateOf(false) }
        Button(onClick = { toggle = !toggle }) {
            Text("Toggle")
        }
        when (toggle) {
            true -> DiscoGameBackground(server)
            false -> DiscoGameInput(server)
        }
    }
}

@Composable
fun DiscoGameBackground(server: WorkshopServer) {
    val background by produceState(initialValue = Color(0, 0, 0)) {
        server.discoGameBackground().collect { value = it.toComposeColor() }
    }
    Spacer(modifier = Modifier.fillMaxSize().background(color = background))
}

@Composable
fun DiscoGameInput(server: WorkshopServer) {
    val pressEvents = remember { MutableSharedFlow<Unit>() }
    val instruction by produceState<DiscoGameInstruction?>(initialValue = null) {
        server.discoGameInstructions(pressEvents).collect { value = it }
    }
    val scope = rememberCoroutineScope()
    Text(toDirectionChar(instruction).toString(), fontSize = 100.sp)
    Button(onClick = { scope.launch { pressEvents.emit(Unit) } }) {
        Text("Press me!")
    }

}

// TODO: Report when bug for Boolean and enums!

// Thanks: https://stackoverflow.com/a/33553752
private fun toDirectionChar(instruction: DiscoGameInstruction?): Char = when (instruction) {
    DiscoGameInstruction.Left -> '←'
    DiscoGameInstruction.LeftUp -> '↖'
    DiscoGameInstruction.Up -> '↑'
    DiscoGameInstruction.RightUp -> '↗'
    DiscoGameInstruction.Right -> '→'
    DiscoGameInstruction.RightDown -> '↘'
    DiscoGameInstruction.Down -> '↓'
    DiscoGameInstruction.LeftDown -> '↙'
    null -> '·'
}

