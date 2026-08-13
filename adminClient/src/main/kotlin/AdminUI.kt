import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ktor.client.*
import io.ktor.http.*
import kmpworkshop.common.*
import kmpworkshop.common.superSecretFallbackPassword
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import workshop.adminaccess.*
import workshop.adminaccess.ScheduledWorkshopEvent.AwaitingResult
import workshop.adminaccess.ScheduledWorkshopEvent.IgnoringResult
import java.nio.file.NoSuchFileException
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun main(): Unit = application {
    AdminApp(onExit = ::exitApplication)
}

private val adminPassword = System.getenv("ADMIN_PASSWORD") ?: superSecretFallbackPassword()

private val workshopStages: List<WorkshopStage> = listOf(WorkshopStage.Registration) +
    WorkshopStage.KotlinBasicsPuzzleStage.entries + WorkshopStage.CoroutinePuzzleStage.entries

private val AdminBackground = Color(0xFFF5F7FB)
private val AdminSurface = Color(0xFFFFFFFF)
private val AdminInk = Color(0xFF182230)
private val AdminMuted = Color(0xFF667085)
private val AdminPrimary = Color(0xFF635BFF)
private val AdminSuccess = Color(0xFF12805C)
private val AdminWarning = Color(0xFFB54708)
private val AdminDanger = Color(0xFFB42318)
private val AdminBorder = Color(0xFFE4E7EC)

private val AdminColors = lightColors(
    primary = AdminPrimary,
    primaryVariant = Color(0xFF4B45CC),
    secondary = AdminSuccess,
    background = AdminBackground,
    surface = AdminSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = AdminInk,
    onSurface = AdminInk,
    error = AdminDanger,
)

suspend fun <T> withAdminAccessService(onUse: suspend CoroutineScope.(AdminAccess) -> T): T {
    val ktorClient = HttpClient {
        installKrpc {
            connector { }
        }
    }

    val client: KtorRpcClient = ktorClient.rpc {
        url {
            protocol = if (serverUrl == "localhost" || serverUrl == "127.0.0.1" || serverUrl == "::1") {
                URLProtocol.WS
            } else {
                URLProtocol.WSS
            }
            host = serverUrl
            port = serverWebsocketPort
            encodedPath = "rpc"
        }

        rpcConfig {
            serialization {
                json()
            }
        }
    }

    val service = client.withService<AdminAccess>()
    return try {
        coroutineScope { onUse(service) }
    } finally {
        client.close()
    }
}

@Composable
fun AdminApp(onExit: () -> Unit) {
    var adminAccessService: AdminAccess? by remember { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) {
                withAdminAccessService { adminAccess ->
                    val serverState = adminAccess
                        .serverState(adminPassword)
                        .shareIn(this, SharingStarted.Eagerly, replay = 1)

                    val adminAccessWithSharedStateFlow = object: AdminAccess by adminAccess {
                        override fun serverState(password: String): Flow<ServerState> = serverState
                    }
                    launch {
                        System.getenv("SERVER_EVENT_BACKUP_DIRECTORY")?.let(::Path)?.sideEffect { backupDir ->
                            backupDir.toFile().mkdirs()
                            try {
                                adminAccess.fire(
                                    adminPassword,
                                    RevertWholeStateEvent(Json.decodeFromString<ServerState>(
                                        backupDir.resolve("adminLocalBackup").readText()),
                                    ),
                                )
                            } catch (e: SerializationException) {
                                backupDir.resolve("unrestorableBackup${Clock.System.now()}").writeText(backupDir.resolve("adminLocalBackup").readText())
                            } catch (e: NoSuchFileException) {
                                // No probs
                            }

                            serverState.drop(2).conflate().collect {
                                backupDir.resolve("adminLocalBackup").writeText(Json.encodeToString(it))
                            }
                        }
                    }
                    launch {
                        while (true) {
                            adminAccess.heartbeat()
                            delay(2.seconds)
                        }
                    }
                    launch {
                        adminAccess.soundEvents(adminPassword).collect { soundEvent ->
                            this@launch.launch { soundEvent.play() }
                        }
                    }
                    adminAccessService = adminAccessWithSharedStateFlow
                    awaitCancellation()
                }
            }
        } finally {
            adminAccessService = null
        }
    }

    adminAccessService
        ?.let { AdminApp(it, onExit) }
        ?: Window(title = "Kotlin Workshop", onCloseRequest = { onExit() }) {
            Text("Workshop is starting!")
        }
}

@Composable
fun AdminApp(adminAccessService: AdminAccess, onExit: () -> Unit) {
    val state by remember { adminAccessService.serverState(adminPassword) }.collectAsState(initial = ServerState())
    val scope = rememberCoroutineScope { Dispatchers.IO }
    AdminApp(state, onEvent = { scheduledEvent ->
        scope.launch {
            when (scheduledEvent) {
                is AwaitingResult<*> -> adminAccessService.continueWithEventResult(scheduledEvent)
                is IgnoringResult -> adminAccessService.fire(adminPassword, scheduledEvent.event)
            }
        }
    }, onExit)
}

private suspend fun <T> AdminAccess.continueWithEventResult(scheduledEvent: AwaitingResult<T>) {
    scheduledEvent.continuation.resumeWith(runCatching {
        Json.decodeFromJsonElement(
            scheduledEvent.event.serializer,
            fire(adminPassword, scheduledEvent.event) ?: error("Event with result did not return a result??")
        )
    })
}

@Composable
fun AdminApp(state: ServerState, onEvent: OnEvent, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()

    WorkshopWindow(
        state = state,
        title = "KMP Workshop",
        onCloseRequest = {
            scope.launch {
                onExit()
            }
        },
        onEvent = onEvent,
        recentBackups = emptyList(),
        whileTimeLineOpen = { awaitCancellation() },
        onTimeLineSelectionChange = { },
        adminUi = { state, onEvent -> AdminUi(state, onEvent) },
        onTimeLineAccept = {},
    )
}

@Composable
fun WorkshopWindow(
    state: ServerState,
    title: String,
    onCloseRequest: () -> Unit,
    onEvent: OnEvent,
    recentBackups: List<Backup> = emptyList(),
    whileTimeLineOpen: suspend () -> Nothing = ::awaitCancellation,
    onTimeLineSelectionChange: (ServerState?) -> Unit = {},
    onTimeLineAccept: () -> Unit = {},
    adminUi: @Composable (ServerState, onEvent: OnEvent) -> Unit,
) {
    Window(onCloseRequest = onCloseRequest, title = title) {
        var settingsIsOpen by remember { mutableStateOf(false) }
        MenuBar {
            Menu("Edit") {
                Item("Settings", shortcut = KeyShortcut(Key.Comma, meta = true), onClick = { settingsIsOpen = true })
            }
        }
        if (settingsIsOpen) {
            SettingsDialog(
                state.settings,
                onDismiss = { settingsIsOpen = false },
                onSettingsChange = { onEvent.schedule(SettingsChangeEvent(it)) },
            )
        }
        MaterialTheme(
            colors = AdminColors,
            typography = Typography(
                defaultFontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                h5 = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AdminInk),
                h6 = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AdminInk),
                body1 = TextStyle(fontSize = 14.sp, color = AdminInk),
                button = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            ),
            shapes = Shapes(
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(18.dp),
            ),
        ) { adminUi(state, onEvent) }
    }
}

@Composable
internal fun SettingsDialog(settings: ServerSettings, onDismiss: () -> Unit, onSettingsChange: (ServerSettings) -> Unit) {
    var currentSettings by remember(settings) { mutableStateOf(settings) }
    var autoSave by remember { mutableStateOf(false) }

    LaunchedEffect(currentSettings, autoSave) {
        if (autoSave && currentSettings != settings) {
            onSettingsChange(currentSettings)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(
                    2.dp,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    color = Color.DarkGray
                )
                .background(Color.White)
                .padding(16.dp),
        ) {
            Row {
                Text("Color dimming: ")
                Slider(
                    currentSettings.dimmingRatio,
                    onValueChange = { currentSettings = currentSettings.copy(dimmingRatio = it) },
                    valueRange = -1f..1f,
                )
            }
            Row {
                Text("Zoom: ")
                Slider(
                    currentSettings.zoom,
                    onValueChange = { currentSettings = currentSettings.copy(zoom = it) },
                    valueRange = 0.1f..3f,
                )
            }
            Row {
                Text("Auto apply changes: ")
                Checkbox(autoSave, onCheckedChange = { autoSave = it })
            }

            Row {
                Button(onClick = { currentSettings = ServerSettings() }) {
                    Text("Reset")
                }
                Button(onClick = { onDismiss() }) {
                    Text("Close")
                }
                if (!autoSave) {
                    Button(onClick = { onSettingsChange(currentSettings) }, enabled = currentSettings != settings) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

private data class IntPoint(val x: Int, val y: Int)
private fun Table.toPoint(): IntPoint = IntPoint(x, y)

private const val tableSize = 5
private const val defaultViewSize = 60.0
private const val gridCellSizeInPixels = 10.0

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TableSetup(state: ServerState, onEvent: OnEvent) {
    var zoom by remember { mutableStateOf(2.0) }
    var currentEnd by remember { mutableStateOf(state.tables.map { it.x }.average() + defaultViewSize / 2) }
    var widthPixels by remember { mutableStateOf(defaultViewSize) }
    var currentPosition by remember { mutableStateOf(Offset.Zero) }
    var mousePosition by remember { mutableStateOf(Offset.Zero) }
    var currentMovingTable by remember { mutableStateOf<Table?>(null) }
    var currentTarget by remember { mutableStateOf<Offset?>(null) }
    var currentSelectedTeam by remember { mutableStateOf(TeamColor.entries.first()) }
    var tipsWindowIsOpen by remember { mutableStateOf(false) }
    var mirroredTablesWindowIsOpen by remember { mutableStateOf(false) }
    // There is a bug in the gesture logic that makes it so that it stores the lambdas between compositions.
    // This means that a direct reference to `state` will result in tables not updating in this lambda.
    // This way it points to a reference to the tables instead. The reference won't update, but the `getValue()` will.
    var stateMutable by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        stateMutable = state
    }
    LaunchedEffect(state.teamCount) {
        currentSelectedTeam = TeamColor.entries[currentSelectedTeam.ordinal.coerceAtMost(state.teamCount - 1)]
    }

    fun Offset.asPixelsToGrid(): IntPoint = asPixelsToGrid(currentPosition, zoom)

    Column {
        Row {
            Button(onClick = { onEvent.schedule(TableAdded(Table(0, 0, null))) }) {
                Text("Add Table")
            }
            Spacer(Modifier.width(10.dp))
            Text("# of participants without table: ${
                state
                    .tables
                    .map { it.assignee }
                    .toSet()
                    .let { assignedApiKeys -> state.participants.count { it.apiKey !in assignedApiKeys } }
            }")
            Spacer(Modifier.weight(1f))
            Button(onClick = { mirroredTablesWindowIsOpen = true }) {
                Text("Mirror view")
            }
            Button(onClick = { tipsWindowIsOpen = true }) {
                Text("?")
            }
        }
        Row {
            Button(
                enabled = state.teamCount < TeamColor.entries.size,
                onClick = { onEvent.schedule(AddTeam) },
            ) {
                Text("Add Team")
            }
            Spacer(Modifier.width(10.dp))
            Button(
                enabled = state.teamCount > 2,
                onClick = { onEvent.schedule(RemoveTeam) },
            ) {
                Text("Remove Team")
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = {
                currentSelectedTeam = TeamColor.entries[(currentSelectedTeam.ordinal + 1) % state.teamCount]
            }) {
                Text("Currently selecting: ${currentSelectedTeam.name}")
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(5.dp, Color.DarkGray)
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val scrollDelta = event.changes.first().scrollDelta
                    val (dx, dy) = scrollDelta
                    if (event.keyboardModifiers.isMetaPressed) {
                        val zoomFactor = 1 + dy / 20
                        zoom *= zoomFactor
                        currentEnd -= (zoom * (widthPixels - mousePosition.x)) * (1 - zoomFactor)
                    } else {
                        currentPosition += scrollDelta * 5f
                        currentEnd += zoom * dx * 5
                    }
                }
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (
                        event.button == PointerButton.Secondary && (
                            !event.keyboardModifiers.isShiftPressed &&
                                !event.keyboardModifiers.isMetaPressed &&
                                !event.keyboardModifiers.isCtrlPressed &&
                                !event.keyboardModifiers.isAltPressed
                            )
                    ) {
                        val (x, y) = event.changes.first().position.asPixelsToGrid()
                        stateMutable.tables.reversed().firstOrNull { table ->
                            x in table.x..< (table.x + tableSize) && y in table.y..< (table.y + tableSize)
                        }?.sideEffect {
                            onEvent.schedule(TableRemoved(it))
                        }
                    }
                }
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.button != PointerButton.Primary) return@onPointerEvent
                    val isShift = event.keyboardModifiers.isShiftPressed && (
                        !event.keyboardModifiers.isMetaPressed &&
                        !event.keyboardModifiers.isCtrlPressed &&
                        !event.keyboardModifiers.isAltPressed
                    )
                    val isMeta = event.keyboardModifiers.isMetaPressed && (
                        !event.keyboardModifiers.isShiftPressed &&
                        !event.keyboardModifiers.isCtrlPressed &&
                        !event.keyboardModifiers.isAltPressed
                    )
                    if (isShift || isMeta) {
                        val (x, y) = event.changes.first().position.asPixelsToGrid()
                        stateMutable.tables.reversed().firstOrNull { table ->
                            x in table.x..< (table.x + tableSize) && y in table.y..< (table.y + tableSize)
                        }
                            ?.assignee
                            ?.let { assignee ->
                                if (isShift) onEvent.schedule(TeamChanged(assignee, currentSelectedTeam))
                                else {
                                    val participant = stateMutable.getParticipantBy(assignee)
                                    if (participant in stateMutable.deactivatedParticipants)
                                        onEvent.schedule(ParticipantReactivationEvent(participant, Random.nextLong()))
                                    else
                                        onEvent.schedule(ParticipantDeactivationEvent(participant))
                                }
                            }
                    }
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    mousePosition = event.changes.first().position
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        matcher = {
                            it.button == PointerButton.Tertiary
                                || (it.keyboardModifiers.isShiftPressed && it.button == PointerButton.Primary)
                        },
                        onDragStart = { },
                        onDragEnd = {},
                        onDragCancel = {},
                        onDrag = {
                            currentEnd -= zoom * it.x
                            currentPosition -= it / zoom.toFloat()
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        matcher = {
                            it.button == PointerButton.Primary && (
                                !it.keyboardModifiers.isShiftPressed &&
                                    !it.keyboardModifiers.isMetaPressed &&
                                    !it.keyboardModifiers.isCtrlPressed &&
                                    !it.keyboardModifiers.isAltPressed
                                )
                        },
                        onDragStart = { offset ->
                            val (gridX, gridY) = offset.asPixelsToGrid()
                            stateMutable.tables.reversed().firstOrNull { table ->
                                gridX in table.x..< (table.x + tableSize)
                                    && gridY in table.y..< (table.y + tableSize)
                            }?.let { targetTable ->
                                currentTarget = offset
                                currentMovingTable = targetTable
                            }
                        },
                        onDragEnd = {
                            currentTarget?.sideEffect { target ->
                                currentMovingTable?.sideEffect { movedTable ->
                                    val (gridX, gridY) = target.asPixelsToGrid()
                                    onEvent.schedule(TableRemoved(movedTable))
                                    onEvent.schedule(TableAdded(movedTable.copy(x = gridX, y = gridY)))
                                }
                            }
                            currentTarget = null
                            currentMovingTable = null
                        },
                        onDragCancel = {
                            currentTarget = null
                            currentMovingTable = null
                        },
                        onDrag = {
                            currentTarget = currentTarget?.plus(it)
                        },
                    )
                }
                .onGloballyPositioned { widthPixels = it.size.width.toDouble() }
            ,
        ) {
            TablesView(state, currentMovingTable, currentTarget, currentPosition, zoom)
        }
    }
    if (tipsWindowIsOpen) {
        Window(title = "Table assigning tips", onCloseRequest = { tipsWindowIsOpen = false }) {
            Text("""
                The following actions can be taken in table assigning mode.
                 - Drag and drop tables.
                 - Shift + click: Sets the table's team to the selected team.
                 - Meta + click: Activate/Deactivate the participant
                 - Right click: Remove the table (not the participant)
            """.trimIndent())
        }
    }
    if (mirroredTablesWindowIsOpen) {
        Window(title = "Mirrored view", onCloseRequest = { mirroredTablesWindowIsOpen = false }) {
            var widthOfMirrorView by remember { mutableStateOf(defaultViewSize) }
            Box(modifier = Modifier.fillMaxWidth().onGloballyPositioned { widthOfMirrorView = it.size.width.toDouble() }) {
                TablesView(
                    state.copy(tables = state.tables.map { it.copy(x = -it.x) }),
                    currentMovingTable?.let { it.copy(x = -it.x) },
                    currentTarget?.let { it.copy(x = -it.x) },
                    currentPosition.copy(x = (currentPosition.x - widthOfMirrorView / zoom).toFloat()),
                    zoom,
                )
            }
        }
    }
}

@Composable
private fun TableView(
    table: Table,
    state: ServerState,
    currentPosition: Offset,
    zoom: Double,
) {
    val (distanceLeft, distanceTop) = table.toPoint().asGridToPixels(currentPosition, zoom)
    if (distanceLeft < 0 || distanceTop < 0) return
    Row {
        Spacer(modifier = Modifier.width(distanceLeft.asPixelsToDp()))
        Column {
            Spacer(modifier = Modifier.height(distanceTop.asPixelsToDp()))
            TableView(table, table.assignee?.let { state.getParticipantBy(it) }, zoom)
        }
    }
}

@Composable
private fun TablesView(
    state: ServerState,
    currentMovingTable: Table?,
    currentTarget: Offset?,
    currentPosition: Offset,
    zoom: Double,
) {
    val deactivatedParticipants = state.deactivatedParticipants.map { it.apiKey }.toSet()
    for (table in state.tables) {
        if (table == currentMovingTable || table.assignee in deactivatedParticipants) {
            Transparent { TableView(table, state, currentPosition, zoom) }
        } else {
            TableView(table, state, currentPosition, zoom)
        }
    }
    currentTarget?.sideEffect { target ->
        currentMovingTable?.sideEffect { oldTable ->
            val (gridX, gridY) = target.asPixelsToGrid(currentPosition, zoom)
            Transparent { TableView(oldTable.copy(x = gridX, y = gridY), state, currentPosition, zoom) }
        }
    }
}

private fun IntPoint.asGridToPixels(currentPosition: Offset, zoom: Double): Offset = Offset(
    x = ((x * gridCellSizeInPixels - currentPosition.x) * zoom).toFloat(),
    y = ((y * gridCellSizeInPixels - currentPosition.y) * zoom).toFloat(),
)

private fun Offset.asPixelsToGrid(currentPosition: Offset, zoom: Double): IntPoint = IntPoint(
    (((x / zoom) + currentPosition.x) / gridCellSizeInPixels).toInt(),
    (((y / zoom) + currentPosition.y) / gridCellSizeInPixels).toInt(),
)

@Composable
fun Transparent(content: @Composable () -> Unit) {
    Box(modifier = Modifier.alpha(0.5f)) {
        content()
    }
}

@Composable
fun TableView(table: Table, participant: Participant?, zoom: Double) {
    BasicText(
        modifier = Modifier
            .size((tableSize * gridCellSizeInPixels * zoom).asPixelsToDp())
            .background(participant?.team?.toComposeColor() ?: Color.DarkGray),
        text  = participant?.name ?: "-",
        autoSize = TextAutoSize.StepBased(minFontSize = 3.sp, maxFontSize = 20.sp),
        style = TextStyle(color = Color.Black, textAlign = TextAlign.Center),
        overflow = TextOverflow.Ellipsis,
    )
}

private fun TeamColor.toComposeColor(): Color = when (this) {
    TeamColor.Red -> Color.Red
    TeamColor.Blue -> Color.Blue
    TeamColor.Green -> Color.Green
    TeamColor.Yellow -> Color.Yellow
    TeamColor.Orange -> Color.Yellow
}.transitionTo(Color.Black, 0.3f)

fun Color.transitionTo(other: Color, ratio: Float): Color = Color(
    red = this.red * (1 - ratio) + other.red * ratio,
    green = this.green * (1 - ratio) + other.green * ratio,
    blue = this.blue * (1 - ratio) + other.blue * ratio,
    alpha = this.alpha * (1 - ratio) + other.alpha * ratio
)

@Composable
fun AdminUi(state: ServerState, onEvent: OnEvent) {
    CompositionLocalProvider(
        LocalDensity provides Density(LocalDensity.current.density * state.settings.zoom)
    ) {
        Column(Modifier.fillMaxSize().background(AdminBackground)) {
            // TODO: Start first pressive tick event when switching to Pressive game!
            StageTopBar(state.currentStage, onEvent)
            when (val stage = state.currentStage) {
                WorkshopStage.Registration -> Registration(state, onEvent)
                is WorkshopStage.KotlinBasicsPuzzleStage -> Puzzle(state, stage.name, onEvent)
                is WorkshopStage.CoroutinePuzzleStage -> Puzzle(state, stage.name, onEvent)
            }
        }
    }
}

@Composable
private fun Puzzle(state: ServerState, puzzleName: String, onEvent: OnEvent) {
    val puzzleState = state.puzzleStates[puzzleName] ?: PuzzleState.Unopened
    when (puzzleState) {
        PuzzleState.Unopened -> Row {
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { onEvent.schedule(PuzzleStartEvent(puzzleName, Clock.System.now())) }) {
                Text("Open puzzle!")
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        is PuzzleState.Opened -> Row(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.participants.map { it.team }.distinct().sortedBy { it.ordinal }.forEach { team ->
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Submissions(puzzleState.asSubmissions(state.participants, team), team)
                }
            }
        }
    }
}

private fun ServerState.getParticipantBy(key: ApiKey): Participant =
    participants.firstOrNull { it.apiKey == key } ?: deactivatedParticipants.first { it.apiKey == key }

@Composable
private fun Submissions(submissions: Submissions, team: TeamColor) {
    val completed = submissions.completedSubmissions.size
    val total = submissions.participants.size
    val progress = if (total == 0) 0f else completed.toFloat() / total
    val accent = team.toComposeColor().transitionTo(Color.White, 0.18f)

    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(AdminSurface)
            .border(1.dp, AdminBorder, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(11.dp).clip(RoundedCornerShape(50)).background(accent))
                Text(
                    "${team.name} team",
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
                Text("$completed / $total", color = AdminMuted, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)),
                color = accent,
                backgroundColor = AdminBorder,
            )
        }
        Divider(color = AdminBorder)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("PARTICIPANT", color = AdminMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("RESULT", color = AdminMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(110.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp)) {
            submissions.participants.forEachIndexed { index, participant ->
                val timeOfCompletion = submissions.completedSubmissions[participant.apiKey]
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (index % 2 == 0) AdminBackground else AdminSurface)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(participant.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.width(110.dp)) {
                        if (timeOfCompletion != null) {
                            val duration = timeOfCompletion - submissions.startTime
                            Text(formatDuration(duration), color = AdminSuccess, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("In progress", color = AdminMuted)
                        }
                    }
                }
            }
        }
    }
}

private fun PuzzleState.Opened.asSubmissions(participants: List<Participant>, team: TeamColor): Submissions {
    val participantsOfThisTeam = participants.filter { it.team == team }
    val keysOfThisTeam = participantsOfThisTeam.map { it.apiKey }.toSet()
    return Submissions(
        startTime,
        participantsOfThisTeam,
        submissions
            .mapKeys { (key, _) -> ApiKey(key) }
            .filter { (key, _) -> key in keysOfThisTeam },
    )
}

private fun formatDuration(duration: Duration): String = when {
    duration < 1.seconds -> "${duration.inWholeMilliseconds}ms"
    duration < 1.minutes -> "${duration.inWholeSeconds}s ${duration.minus(duration.inWholeSeconds.seconds).inWholeMilliseconds}ms"
    else -> "${duration.inWholeMinutes}m ${duration.minus(duration.inWholeMinutes.minutes).inWholeSeconds}s"
}

@Composable
private fun StageTopBar(stage: WorkshopStage, onEvent: OnEvent) {
    Row(
        modifier = Modifier.fillMaxWidth().background(AdminSurface).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoveStageButton(stage, onEvent, -1, Key.DirectionLeft) {
            Text("←", fontSize = 18.sp)
        }
        Spacer(modifier = Modifier.width(18.dp))
        Column {
            Text("WORKSHOP STAGE", color = AdminMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            var expanded by remember { mutableStateOf(false) }
            ClickableText(
                text = AnnotatedString(stage.kotlinFile),
                style = MaterialTheme.typography.h5.copy(color = AdminInk),
                onClick = { expanded = true }
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                workshopStages.forEach {
                    DropdownMenuItem(onClick = { expanded = false; onEvent.schedule(StageChangeEvent(it)) }) {
                        Text(it.kotlinFile)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        MoveStageButton(stage, onEvent, 1, Key.DirectionRight) {
            Text("→", fontSize = 18.sp)
        }
    }
    Divider(
        color = AdminBorder,
        thickness = 1.dp,
    )
}

@Composable
private fun MoveStageButton(
    stage: WorkshopStage,
    onEvent: OnEvent,
    offset: Int,
    key: Key, // TODO: Make work?
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        enabled = stage.moving(offset) != null,
        onClick = { onEvent.schedule(StageChangeEvent(stage.moving(offset)!!)) },
        content = content,
    )
}

private fun WorkshopStage.moving(offset: Int): WorkshopStage? =
    workshopStages.getOrNull(workshopStages.indexOf(this) + offset)

private enum class RegistrationViewMode {
    ParticipantRegistration, TableSetup,
}

@Composable
private fun Registration(state: ServerState, onEvent: OnEvent) {
    var registrationMode by remember { mutableStateOf(RegistrationViewMode.entries.first()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
        Text("Registration", style = MaterialTheme.typography.h5)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = {
            registrationMode = RegistrationViewMode.entries[(registrationMode.ordinal + 1) % RegistrationViewMode.entries.size]
        }) {
            Text(if (registrationMode == RegistrationViewMode.ParticipantRegistration) "Table setup" else "Participant list")
        }
    }
    when (registrationMode) {
        RegistrationViewMode.ParticipantRegistration -> RegistrationList(state, onEvent)
        RegistrationViewMode.TableSetup -> TableSetup(state, onEvent)
    }
}

@Composable
private fun RegistrationList(state: ServerState, onEvent: OnEvent) {
    var searchText by remember { mutableStateOf("") }
    fun List<Participant>.filtered(): List<Participant> =
        if (searchText.isBlank()) this else filter { it.name.contains(searchText, ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).clip(RoundedCornerShape(16.dp)).background(AdminSurface).border(1.dp, AdminBorder, RoundedCornerShape(16.dp))) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Participants", style = MaterialTheme.typography.h6)
                Text("${state.participants.size} active · ${state.unverifiedParticipants.size} pending", color = AdminMuted)
            }
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Search participants…") },
                singleLine = true,
                modifier = Modifier.width(300.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(focusedBorderColor = AdminPrimary, unfocusedBorderColor = AdminBorder),
            )
        }
        Divider(color = AdminBorder)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            ParticipantSection("Active", state.participants.filtered(), AdminSuccess) { participant ->
                OutlinedButton(onClick = { onEvent.schedule(ParticipantDeactivationEvent(participant)) }) { Text("Deactivate") }
            }
            ParticipantSection("Pending approval", state.unverifiedParticipants.filtered(), AdminWarning) { participant ->
                OutlinedButton(onClick = { onEvent.schedule(ParticipantRejectionEvent(participant)) }) { Text("Reject") }
            }
            ParticipantSection("Deactivated", state.deactivatedParticipants.filtered(), AdminMuted) { participant ->
                TextButton(onClick = { onEvent.schedule(ParticipantRemovalEvent(participant)) }, colors = ButtonDefaults.textButtonColors(contentColor = AdminDanger)) { Text("Delete") }
                Button(onClick = { onEvent.schedule(ParticipantReactivationEvent(participant, Random.nextLong())) }) { Text("Activate") }
            }
        }
    }
}

@Composable
private fun ParticipantSection(
    title: String,
    participants: List<Participant>,
    accent: Color,
    actions: @Composable RowScope.(Participant) -> Unit,
) {
    if (participants.isEmpty()) return
    Text(title.uppercase(), color = AdminMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp))
    participants.forEach { participant ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(AdminBackground).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(accent))
            Text(participant.name, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp).weight(1f))
            actions(participant)
        }
    }
}

@Composable
private fun Double.asPixelsToDp(): Dp = (this / LocalDensity.current.density).coerceAtMost(5_000.0).dp
@Composable
private fun Float.asPixelsToDp(): Dp = toDouble().asPixelsToDp()
