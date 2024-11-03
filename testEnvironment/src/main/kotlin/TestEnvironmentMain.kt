import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kmpworkshop.client.AdaptingBackground
import kmpworkshop.common.ApiKey
import kmpworkshop.server.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

suspend fun main(): Unit = coroutineScope {
    val serverState = MutableStateFlow(ServerState(
        participants = listOf(
            Participant("John", ApiKey("JohnKey")),
            Participant("Jane", ApiKey("JaneKey")),
            Participant("Alice", ApiKey("AliceKey")),
        ),
        currentStage = WorkshopStage.PressiveGameStage,
    ).startingThirdPressiveGame())

    launch(Dispatchers.Default) {
        performScheduledEvents(serverState)
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "Test environment") {
            androidx.compose.material.MaterialTheme {
                CanvasScreen(serverState)
            }
        }
    }
}

@Composable
fun ResizableDraggableItem(
    initialWidth: Dp = 100.dp,
    initialHeight: Dp = 100.dp,
    content: @Composable () -> Unit
) {
    // Maintain the offset position and size of the Composable
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var width by remember { mutableStateOf(initialWidth) }
    var height by remember { mutableStateOf(initialHeight) }

    Column(
        modifier = Modifier
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .width(width)
            .height(height)
            .wrapContentSize()
    ) {
        // Draggable top bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(Color.Gray)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        )

        // Content of the resizable, draggable item
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            content()

            // Bottom-right resize handle
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(Color.DarkGray)
                    .align(Alignment.BottomEnd)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            // Adjust width and height based on drag amount
                            width += dragAmount.x.dp / 2
                            height += dragAmount.y.dp / 2
                        }
                    }
            )
        }
    }
}

@Composable
fun CanvasScreen(serverState: MutableStateFlow<ServerState>) {
    val state by produceState(initialValue = ServerState()) {
        serverState.collect { value = it }
    }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.LightGray)
    ) {
        ResizableDraggableItem {
            ServerUi(state, onStateChange = { stateUpdater ->
                scope.launch(Dispatchers.Default) { serverState.update { stateUpdater(it) } }
            })
        }
        state.participants.forEach { participant ->
            val server = workshopServer(scope.coroutineContext, serverState, participant.apiKey)
            ResizableDraggableItem {
                AdaptingBackground(server) {
                    PressiveGameSolution(server)
                }
            }
        }
    }
}
