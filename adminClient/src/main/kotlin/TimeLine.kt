@file:OptIn(ExperimentalTime::class)

import androidx.compose.runtime.Composable
import workshop.adminaccess.Backup
import workshop.adminaccess.Participant
import workshop.adminaccess.ServerState
import kotlin.time.ExperimentalTime

@Composable
internal fun TimeLine(
    participants: List<Participant>,
    onClose: () -> Unit,
    recentBackups: List<Backup>,
    whileTimeLineOpen: suspend () -> Nothing,
    onSelectionChange: (ServerState?) -> Unit,
    onTimeLineAccept: () -> Unit,
) {
//    val backups by produceState<List<BackupFetchResult>?>(initialValue = null) {
//        value = getAllBackups()
//    }
//
//    Window(
//        title = "Time Machine",
//        onCloseRequest = { onClose() },
//        state = rememberWindowState(
//            width = 1000.dp,
//            height = 200.dp,
//            position = WindowPosition(0.dp, 1000.dp),
//        ),
//        onKeyEvent = {
//            (it.isMetaPressed && it.key == Key.W || it.key == Key.Escape)
//                .also { if (it) onClose() }
//        },
//    ) {
//        backups
//            ?.let { TimeLine(participants, it, recentBackups, whileTimeLineOpen, onSelectionChange, onTimeLineAccept) }
//            ?: Row {
//                Text("Backups loading...")
//                // Spinner ?
//            }
//    }
}
//
//sealed class FetchedEvent {
//    data class Success(val timedEvent: TimedEvent) : FetchedEvent()
//    data class Failed(val time: Instant) : FetchedEvent()
//}
//
//val FetchedEvent.time: Instant get() = when (this) {
//    is FetchedEvent.Failed -> time
//    is FetchedEvent.Success -> timedEvent.time
//}
//
//@Composable
//internal fun TimeLine(
//    participants: List<Participant>,
//    backupFetchResults: List<BackupFetchResult>,
//    recentBackups: List<Backup>,
//    whileTimeLineOpen: suspend () -> Nothing,
//    onSelectionChange: (ServerState?) -> Unit,
//    onTimeLineAccept: () -> Unit,
//) {
//    LaunchedEffect(Unit) {
//        whileTimeLineOpen()
//    }
//    val allBackups = remember(backupFetchResults, recentBackups) {
//        backupFetchResults + recentBackups.map { BackupFetchResult.Success(it) }
//    }
//    val allEvents = remember(allBackups) { allEventFetchResults(allBackups) }
//    var selection by remember { mutableStateOf(Clock.System.now()) }
//    val selectedEvent: FetchedEvent? = remember(allEvents, selection) {
//        if (selection > Clock.System.now()) null
//        else allEvents.binarySearch { it.time.compareTo(selection) }.let { insertionPoint ->
//            if (insertionPoint > 0) allEvents[insertionPoint]
//            else if (insertionPoint == -1) null
//            else allEvents[-insertionPoint - 2]
//        }
//    }
//
//    LaunchedEffect(selectedEvent) {
//        val event = (selectedEvent as? FetchedEvent.Success)?.timedEvent ?: run {
//            onSelectionChange(null)
//            return@LaunchedEffect
//        }
//        allBackups
//            .mapNotNull { (it as? BackupFetchResult.Success)?.backup }
//            .first { event in it.events }
//            .let { backup ->
//                (backup.events.takeWhile { it != event } + event)
//                    .fold(backup.initial) { acc, event -> acc.after(event.event) }
//            }.let { onSelectionChange(it) }
//    }
//
//    TimelineCanvas(
//        modifier = Modifier.fillMaxSize(),
//        defaultEnd = Clock.System.now(),
//        onSelectionMove = { selection = it },
//    ) { y, start, end, durationPerPixel ->
//        val visibleEvents = remember(allEvents, start, end) { allEvents.betweenMinusOne(start, end) { it.time } }
//        if (visibleEvents.isEmpty()) return@TimelineCanvas
//        var endOrNow by remember { mutableStateOf(end) }
//        LaunchedEffect(end) {
//            while (end > Clock.System.now()) {
//                endOrNow = end.coerceAtMost(Clock.System.now())
//                delay(durationPerPixel)
//            }
//        }
//        Box {
//            Column {
//                Row(modifier = Modifier.height(30.dp)) {
//                    val firstEvent = visibleEvents.first()
//                    if (firstEvent.time > start) {
//                        Spacer(modifier = Modifier.width(((firstEvent.time - start) / durationPerPixel).asPixelsToDp()))
//                    }
//                    for ((event, nextEvent) in visibleEvents.zipWithNext()) {
//                        TimeLineEvent(
//                            event,
//                            isSelected = selectedEvent == event,
//                            modifier = Modifier
//                                .width(((nextEvent.time - event.time.coerceAtLeast(start)) / durationPerPixel).asPixelsToDp()),
//                        )
//                    }
//                    if (endOrNow > start) {
//                        val lastEvent = visibleEvents.last()
//                        TimeLineEvent(
//                            lastEvent,
//                            isSelected = selectedEvent == lastEvent,
//                            modifier = Modifier
//                                .width(((endOrNow - lastEvent.time.coerceAtLeast(start)) / durationPerPixel).asPixelsToDp()),
//                        )
//                    }
//                }
//                TimeLineOverview(start, end, durationPerPixel)
//                Button(onClick = { onTimeLineAccept() }, enabled = selectedEvent is FetchedEvent.Success) {
//                    Text("Revert to Selected state")
//                }
//            }
//            if (selection in start..end) {
//                Row(modifier = Modifier.height(45.dp).fillMaxWidth()) {
//                    Spacer(
//                        modifier = Modifier
//                            .fillMaxHeight()
//                            .width(((selection - start) / durationPerPixel).asPixelsToDp())
//                    )
//                    Spacer(
//                        modifier = Modifier
//                            .fillMaxHeight()
//                            .width(3.dp)
//                            .background(Color.Green.transitionTo(Color.Black, .3f))
//                    )
//                }
//            }
//        }
//    }
//}
//
//@Composable
//private fun TimeLineEvent(firstEvent: FetchedEvent, isSelected: Boolean, modifier: Modifier) {
//    Spacer(
//        modifier = modifier
//            .fillMaxHeight()
//            .applyIf({ firstEvent is FetchedEvent.Failed }) { it.background(Color.Red.transitionTo(Color.Black, .3f)) }
//            .applyIf({ isSelected }) { it.background(Color.Gray) }
//            .border(1.dp, Color.Black)
//        ,
//    )
//}
//
//private fun allEventFetchResults(backupFetchResults: List<BackupFetchResult>) =
//    backupFetchResults.flatMap { backupFetchResult ->
//        when (backupFetchResult) {
//            is BackupFetchResult.Success -> backupFetchResult.backup.events.map { FetchedEvent.Success(it) }
//            is BackupFetchResult.Outdated -> listOf(FetchedEvent.Failed(backupFetchResult.startTime))
//        }
//    }
//
//@Composable
//private fun TimeLineOverview(
//    start: Instant,
//    end: Instant,
//    durationPerPixel: Duration,
//) {
//    val duration = (end - start)
//    val level = generateSequence(dayLevel) { it.smallerStep }
//        .firstOrNull { it.interval * 1.3 < duration }
//        ?: secondsLevel
//    Box {
//        Row(modifier = Modifier.fillMaxWidth().height(20.dp)) {
//            Ticks(start, end, level.interval, durationPerPixel)
//        }
//        level.smallerStep?.let { nextLevel ->
//            Row(modifier = Modifier.fillMaxWidth().height(10.dp)) {
//                Ticks(start, end, nextLevel.interval, durationPerPixel)
//            }
//        }
//    }
//    TickTimeTexts(start, end, level, durationPerPixel)
//    Row(modifier = Modifier.fillMaxWidth()) {
//        Text(level.moduloFormatter(start))
//        Spacer(modifier = Modifier.weight(1f))
//        Text(level.moduloFormatter(end))
//    }
//}
//
//@Composable
//private fun Ticks(
//    start: Instant,
//    end: Instant,
//    interval: Duration,
//    durationPerPixel: Duration
//) {
//    val firstOffset = start.roundDownBy(interval)
//    if (start != firstOffset) {
//        Tick((firstOffset - start + interval) / durationPerPixel)
//    }
//    // TODO: Replace with repeat
//    ((firstOffset + interval)..end.roundDownBy(interval)).step(interval).forEach {
//        Tick(interval / durationPerPixel)
//    }
//}
//
//@Composable
//private fun TickTimeTexts(
//    realStart: Instant,
//    realEnd: Instant,
//    level: TimeLevel,
//    durationPerPixel: Duration,
//) {
//    val start = realStart + level.interval / 2
//    val firstOffset = start.roundDownBy(level.interval)
//    Row {
//        if (start != firstOffset) {
//            Spacer(modifier = Modifier.width(((firstOffset - start + level.interval) / durationPerPixel).asPixelsToDp()))
//        }
//        generateSequence(firstOffset) { it + level.interval }
//            .map { it + level.interval }
//            .takeWhile { it <= realEnd - level.interval * 0.5 }
//            .forEach {
//                Box(
//                    modifier = Modifier.width((level.interval / durationPerPixel).asPixelsToDp()),
//                    contentAlignment = Alignment.Center,
//                ) {
//                    Text(text = level.tickFormatter(it))
//                }
//            }
//    }
//}
//
//private fun ClosedRange<Instant>.step(interval: Duration): Sequence<Instant> =
//    generateSequence(start) { it + interval }.takeWhile { it <= endInclusive }
//
//@Composable
//private fun Tick(pixels: Double) {
//    Row(Modifier.fillMaxHeight().width(pixels.asPixelsToDp())) {
//        Spacer(modifier = Modifier.weight(1f))
//        Spacer(Modifier.fillMaxHeight().width(1.dp).background(Color.Black))
//    }
//}
//
//private fun Instant.roundDownBy(interval: Duration): Instant =
//    this - ((nanosecondsOfSecond % interval.inWholeNanoseconds).nanoseconds + (epochSeconds % interval.inWholeSeconds).seconds)
//
//private fun Instant.tensOfMinutes(): Int = format(minute).toInt() / 10
//private fun Instant.tensOfSeconds(): Int = format(second).toInt() / 10
//
//data class TimeLevel(
//    val moduloFormatter: (Instant) -> String,
//    val interval: Duration,
//    val tickFormatter: (Instant) -> String,
//    val smallerStep: TimeLevel?,
//)
//
//@Composable
//private fun Double.asPixelsToDp(): Dp = (this / LocalDensity.current.density).coerceAtMost(5_000.0).dp
//
///** Also gets the one element before [start] if it exists */
//private inline fun <T> List<T>.betweenMinusOne(start: Instant, end: Instant, getTime: (T) -> Instant): List<T> =
//    if (isEmpty()) emptyList()
//    else subList(
//        fromIndex = indexOfFirst { getTime(it) > start }.takeIf { it > 0 }.let { it ?: 1 } - 1,
//        toIndex = indexOfFirst { getTime(it) > end }.takeUnless { it == -1 } ?: size,
//    )
//
//@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
//@Composable
//fun TimelineCanvas(
//    modifier: Modifier = Modifier,
//    defaultEnd: Instant = Clock.System.now(),
//    onSelectionMove: (position: Instant) -> Unit,
//    content: @Composable (y: Float, start: Instant, end: Instant, durationPerPixel: Duration) -> Unit,
//) {
//    var zoomAsDurationPerPixel by remember { mutableStateOf(500.milliseconds) }
//    var currentEnd by remember { mutableStateOf(defaultEnd) }
//    var widthPixels by remember { mutableStateOf(0) }
//    val currentStart = remember(widthPixels, zoomAsDurationPerPixel, currentEnd) {
//        currentEnd - widthPixels * zoomAsDurationPerPixel
//    }
//    var currentY by remember { mutableStateOf(0f) }
//    var mousePosition by remember { mutableStateOf(Offset.Zero) }
//
//    Box(
//        modifier = modifier
//            .onPointerEvent(PointerEventType.Scroll) { event ->
//                val (dx, dy) = event.changes.first().scrollDelta
//                if (event.keyboardModifiers.isMetaPressed) {
//                    val zoomFactor = 1 + dy / 20
//                    zoomAsDurationPerPixel *= zoomFactor
//                    currentEnd -= (zoomAsDurationPerPixel * (widthPixels - mousePosition.x)) * (1 - zoomFactor)
//                } else {
//                    currentY += dy * 5
//                    currentEnd += zoomAsDurationPerPixel * dx * 5
//                }
//            }
//            .onPointerEvent(PointerEventType.Press) { event ->
//                if (
//                    event.button == PointerButton.Primary && (
//                        !event.keyboardModifiers.isShiftPressed &&
//                        !event.keyboardModifiers.isMetaPressed &&
//                        !event.keyboardModifiers.isCtrlPressed &&
//                        !event.keyboardModifiers.isAltPressed
//                    )
//                ) {
//                    onSelectionMove(currentEnd + zoomAsDurationPerPixel * (mousePosition.x - widthPixels))
//                }
//            }
//            .onPointerEvent(PointerEventType.Move) { event ->
//                mousePosition = event.changes.first().position
//            }
//            .pointerInput(Unit) {
//                detectDragGestures(
//                    matcher = {
//                        it.button == PointerButton.Tertiary
//                            || (it.keyboardModifiers.isShiftPressed && it.button == PointerButton.Primary)
//                    },
//                    onDragStart = { },
//                    onDragEnd = {},
//                    onDragCancel = {},
//                    onDrag = { (x, y) ->
//                        currentEnd -= zoomAsDurationPerPixel * x
//                        currentY -= y
//                    },
//                )
//            }
//            .pointerInput(Unit) {
//                detectDragGestures(
//                    matcher = {
//                        it.button == PointerButton.Primary && (
//                            !it.keyboardModifiers.isShiftPressed &&
//                            !it.keyboardModifiers.isMetaPressed &&
//                            !it.keyboardModifiers.isCtrlPressed &&
//                            !it.keyboardModifiers.isAltPressed
//                        )
//                    },
//                    onDragStart = {},
//                    onDragEnd = {},
//                    onDragCancel = {},
//                    onDrag = { (x, y) ->
//                        onSelectionMove(currentEnd + zoomAsDurationPerPixel * (mousePosition.x - widthPixels))
//                    },
//                )
//            }
//            .onGloballyPositioned { widthPixels = it.size.width }
//        ,
//    ) {
//        if (widthPixels > 0) {
//            content(currentY, currentStart, currentEnd, zoomAsDurationPerPixel)
//        }
//    }
//}
//
//private operator fun Duration.times(multiplier: Float): Duration = this * multiplier.toDouble()
//
//private val year = DateTimeComponents.Format {
//    year()
//}
//
//private val month = DateTimeComponents.Format {
//    monthName(MonthNames.ENGLISH_ABBREVIATED)
//}
//
//private val dayOfMonth = DateTimeComponents.Format {
//    dayOfMonth()
//}
//
//private val hour = DateTimeComponents.Format {
//    hour()
//}
//
//private val minute = DateTimeComponents.Format {
//    minute()
//}
//
//private val second = DateTimeComponents.Format {
//    second()
//}
//
//val secondsLevel = TimeLevel(
//    moduloFormatter = { "${it.format(year)}-${it.format(month)}-${it.format(dayOfMonth)} ${it.format(hour)}:${it.format(minute)}:${it.tensOfSeconds()}." },
//    interval = 1.seconds,
//    tickFormatter = { "${it.format(second).toInt() % 10}" },
//    smallerStep = null,
//)
//
//val tenSecondsLevel = TimeLevel(
//    moduloFormatter = { "${it.format(year)}-${it.format(month)}-${it.format(dayOfMonth)} ${it.format(hour)}:${it.format(minute)}:.." },
//    interval = 10.seconds,
//    tickFormatter = { it.format(second) },
//    smallerStep = secondsLevel,
//)
//
//val minuteLevel = TimeLevel(
//    moduloFormatter = { "${it.format(year)}-${it.format(month)}-${it.format(dayOfMonth)} ${it.format(hour)}:${it.tensOfMinutes()}.:.." },
//    interval = 1.minutes,
//    tickFormatter = { "${it.format(minute).toInt() % 10}:00" },
//    smallerStep = tenSecondsLevel,
//)
//
//val tenMinuteLevel = TimeLevel(
//    moduloFormatter = { "${it.format(year)}-${it.format(month)}-${it.format(dayOfMonth)} ${it.format(hour)}:.." },
//    interval = 10.minutes,
//    tickFormatter = { it.format(minute) },
//    smallerStep = minuteLevel,
//)
//
//val hourLevel = TimeLevel(
//    moduloFormatter = { "${it.format(year)}-${it.format(month)}-${it.format(dayOfMonth)} ..:.." },
//    interval = 1.hours,
//    tickFormatter = { "${it.format(hour)}:00" },
//    smallerStep = tenMinuteLevel,
//)
//
//val dayLevel = TimeLevel(
//    moduloFormatter = { "${it.format(year)}-${it.format(month)}-.." },
//    interval = 1.days,
//    tickFormatter = { it.format(dayOfMonth) },
//    smallerStep = hourLevel,
//)
