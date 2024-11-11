import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kmpworkshop.client.toComposeColor
import kmpworkshop.common.WorkshopServer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun DiscoGameSolution(server: WorkshopServer) {
    Box {
        DiscoGameBackground(server)
        DiscoGameInput(server)
    }
}

@Composable
fun DiscoGameBackground(server: WorkshopServer) {
    val background by remember {
        server.discoGameBackground().map { it.toComposeColor() }
    }.collectAsState(initial = Color(0, 0, 0))
    Spacer(modifier = Modifier.fillMaxSize().background(color = background))
}

@Composable
fun DiscoGameInput(server: WorkshopServer) {
    val pressEvents = remember { MutableSharedFlow<Unit>() }
    val instruction by remember { server.discoGameInstructions(pressEvents) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    Column {
        Text((instruction?.char ?: '·').toString(), fontSize = 100.sp)
        Button(onClick = { scope.launch { pressEvents.emit(Unit) } }) {
            Text("Press me!")
        }
    }
}
