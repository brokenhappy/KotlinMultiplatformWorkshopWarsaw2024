package kmpworkshop.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kmpworkshop.common.DiscoGameInstruction
import kotlinx.coroutines.flow.Flow

@Composable
fun DiscoGame(server: DiscoGameServer) {
    val background = remember { server.backgroundColors() }.collectAsState(initial = Color.White)
    val instruction = remember { server.instructions() }.collectAsState(initial = null)

    Column {
        Text("Elements in a Column")
        Text("Are painted under and above each other")
        Box {
            Text("Elements in a Box")
            Text("Are painted on top of each other", fontSize = 30.sp)
        }
        Row {
            Text("Elements in a Row")
            Text("Are painted next to each other")
        }
    }
}

interface DiscoGameServer {
    fun backgroundColors(): Flow<Color>
    fun instructions(): Flow<DiscoGameInstruction?>
    suspend fun submitGuess()
}