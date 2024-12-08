import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.onClick
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kmpworkshop.client.getFlowOfPressiveGameHints
import kmpworkshop.common.PressiveGamePressType
import kmpworkshop.common.WorkshopServer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PressiveGameSolution(server: WorkshopServer) {
    val pressEvents = remember { MutableSharedFlow<PressiveGamePressType>() }
    suspend fun doSinglePress(): Unit = pressEvents.emit(PressiveGamePressType.SinglePress)
    suspend fun doDoublePress(): Unit = pressEvents.emit(PressiveGamePressType.DoublePress)
    suspend fun doLongPress(): Unit = pressEvents.emit(PressiveGamePressType.LongPress)

    val hint by remember { server.playPressiveGame(pressEvents) }.collectAsState(initial = "Connecting to host...")
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier.onClick(
            onClick = { scope.launch { doSinglePress() } },
            onDoubleClick = { scope.launch { doDoublePress() } },
            onLongClick = { scope.launch { doLongPress() } },
        )
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Column {
            Spacer(modifier = Modifier.weight(1f))
            androidx.compose.material.Text(hint)
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
