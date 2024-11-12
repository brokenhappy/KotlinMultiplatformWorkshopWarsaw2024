import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kmpworkshop.client.DiscoGameServer
import kotlinx.coroutines.launch

@Composable
internal fun DiscoGameSolution(server: DiscoGameServer) {
    Box {
        DiscoGameBackground(server)
        DiscoGameInput(server)
    }
}

@Composable
private fun DiscoGameBackground(server: DiscoGameServer) {
    val background by remember { server.backgroundColors() }.collectAsState(initial = Color(0, 0, 0))
    Spacer(modifier = Modifier.fillMaxSize().background(color = background))
}

@Composable
private fun DiscoGameInput(server: DiscoGameServer) {
    val instruction by remember { server.instructions() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    Column {
        Text((instruction?.char ?: '·').toString(), fontSize = 100.sp)
        Button(onClick = { scope.launch { server.submitGuess() } }) {
            Text("Press me!")
        }
    }
}
