package kmpworkshop.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kmpworkshop.common.DiscoGameInstruction
import kmpworkshop.common.WorkshopServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun DiscoGame(server: DiscoGameServer) {
    val background by remember { server.backgroundColors() }.collectAsState(initial = Color.White)
    val instruction by remember { server.instructions() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    Box {
        Spacer(modifier = Modifier.fillMaxSize().background(color = background))
        Column {
            Text((instruction?.char ?: '·').toString(), fontSize = 100.sp)
            Button(onClick = { scope.launch { server.submitGuess() } }) {
                Text("Press me!")
            }
        }
    }
}

interface DiscoGameServer {
    fun backgroundColors(): Flow<Color>
    fun instructions(): Flow<DiscoGameInstruction?>
    suspend fun submitGuess()
}
