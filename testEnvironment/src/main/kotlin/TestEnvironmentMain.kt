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
import androidx.compose.ui.window.application
import kmpworkshop.client.ClientEntryPoint
import kmpworkshop.client.ClientSettings
import kmpworkshop.client.defaultClientMetadata
import kmpworkshop.common.*
import kmpworkshop.server.mainEventLoopWritingTo
import kmpworkshop.server.workshopService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import workshop.adminaccess.OnEvent
import workshop.adminaccess.Participant
import workshop.adminaccess.ScheduledWorkshopEvent
import workshop.adminaccess.ServerState

data class WorkshopServiceAndEventLoop(
    val workshopService: WorkshopApiService,
    val onEvent: OnEvent,
    val state: Flow<ServerState>
)

fun testWorkshopService(initialState: ServerState): Resource<WorkshopServiceAndEventLoop> = resource { consume ->
    val serverState = MutableStateFlow(initialState)
    val eventBus = Channel<ScheduledWorkshopEvent>(Channel.UNLIMITED)
    val eventLoop = launch(Dispatchers.Default) {
        mainEventLoopWritingTo(
            serverState,
            eventBus,
            onCommittedState = { },
            onSoundEvent = { },
            onEvent = eventBus::trySend,
        )
    }
    try {
        consume(WorkshopServiceAndEventLoop(
            workshopService(serverState, onEvent = eventBus::trySend),
            onEvent = eventBus::trySend,
            serverState,
        ))
    } finally {
        eventLoop.cancel()
        eventBus.close()
    }
}

suspend fun main(): Unit = coroutineScope {
    testWorkshopService(
        initialState = ServerState(
            participants = listOf(
                Participant("John", ApiKey("JohnKey")),
                Participant("Jane", ApiKey("JaneKey")),
                Participant("Alice", ApiKey("AliceKey")),
                Participant("Jobber", ApiKey("JobberKey")),
            ),
    //        participants = (0..49).map { Participant("Participant $it", ApiKey("$it")) },
            currentStage = WorkshopStage.Registration,
        )
    ).use { (service, onEvent, serverState) ->
        application {
            val state by serverState.collectAsState(initial = ServerState())
            WorkshopWindow(
                onCloseRequest = ::exitApplication,
                title = "Test environment",
                state = state,
                onEvent = onEvent,
                adminUi = { state, onEvent -> CanvasScreen(state, service, onEvent) }
            )
        }
    }
}

@Composable
fun ResizableDraggableItem(
    initialWidth: Dp = 100.dp,
    initialHeight: Dp = 100.dp,
    initialOffsetX: Float = 0f,
    initialOffsetY: Float = 0f,
    content: @Composable () -> Unit,
) {
    var offsetX by remember { mutableStateOf(initialOffsetX) }
    var offsetY by remember { mutableStateOf(initialOffsetY) }
    var width by remember { mutableStateOf(initialWidth) }
    var height by remember { mutableStateOf(initialHeight) }

    Column(
        modifier = Modifier
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .width(width)
            .height(height)
            .wrapContentSize()
    ) {
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

        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            content()
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
fun CanvasScreen(state: ServerState, service: WorkshopApiService, onEvent: OnEvent) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.LightGray)
    ) {
        ResizableDraggableItem(initialWidth = 500.dp, initialHeight = 750.dp) {
            AdminUi(state, onEvent)
        }
        state.participants.forEachIndexed { index, participant ->
            val server = remember { service.asServer(participant.apiKey) }
            ResizableDraggableItem(
                initialWidth = 194.dp,
                initialHeight = 242.dp,
                initialOffsetX = 1000f + index % 4 * 386f,
                initialOffsetY = index / 4 * 484f
            ) {
                FunctionUnderTest(server)
            }
        }
    }
}

@Composable
fun FunctionUnderTest(server: WorkshopServer) {
    val clientSettings = remember { MutableStateFlow(ClientSettings()) }
    ClientEntryPoint(
        server,
        clientMetadata = defaultClientMetadata,
        clientSettings = clientSettings,
        onClientSettingsChange = { newSettings -> clientSettings.value = newSettings },
        submitBugReport = { throw NotImplementedError() },
        openReportBug = false,
        onReportBugClosed = {},
    )
}
