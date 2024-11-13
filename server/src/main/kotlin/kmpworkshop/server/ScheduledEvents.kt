package kmpworkshop.server

import kmpworkshop.common.DiscoGameInstruction
import kmpworkshop.common.SerializableColor
import kmpworkshop.server.TimedEventType.PressiveGameTickEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.*
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

suspend fun performScheduledEvents(serverState: MutableStateFlow<ServerState>, eventBus: ReceiveChannel<ScheduledWorkshopEvent>): Nothing {
    coroutineScope {
        val events = Channel<CommittedState>()
        launch {
            try {
                val initial = loadInitialStateFromDatabase()
                if (initial != ServerState()) serverState.value = initial
                storeEvents(initial, events)
            } catch (t: Throwable) {
                t.printStackTrace()
                throw t
            }
        }
        launch {
            try {
                for (scheduledEvent in eventBus) {
                    when (scheduledEvent) {
                        is ScheduledWorkshopEvent.AwaitingResult<*> -> {
                            serverState.applyEventWithResult(
                                applicationScope = this,
                                scheduledEvent,
                                onCommittedState = { launch { events.send(it) } }
                            )
                        }
                        is ScheduledWorkshopEvent.IgnoringResult -> {
                            var persistedState: CommittedState? = null
                            serverState.update { oldState ->
                                try {
                                    oldState.after(scheduledEvent.event).also { newState ->
                                        persistedState = CommittedState(oldState, scheduledEvent.event, newState)
                                    }
                                } catch (c: CancellationException) {
                                    throw c
                                } catch (t: Throwable) {
                                    launch { reportError(oldState, scheduledEvent.event) }
                                    oldState
                                }
                            }
                            persistedState?.let { launch { events.send(it) } }
                        }
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                throw t
            }
        }
        serverState
            .map { it.scheduledEvents.minByOrNull { it.time } }
            .distinctUntilChangedBy { it?.time }
            .collectLatest { firstScheduledEvent ->
                try {
                    if (firstScheduledEvent == null) return@collectLatest
                    delayUntil(firstScheduledEvent.time)
                    serverState.update {
                        it.copy(scheduledEvents = it.scheduledEvents - firstScheduledEvent).after(firstScheduledEvent.type)
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    t.printStackTrace()
                    throw t
                }
            }
    }
    error("Should not be reached")
}

private fun <T> MutableStateFlow<ServerState>.applyEventWithResult(
    applicationScope: CoroutineScope,
    scheduledEvent: ScheduledWorkshopEvent.AwaitingResult<T>,
    onCommittedState: (CommittedState) -> Unit,
): Result<T> {
    val result = runCatching {
        var result: T? = null
        var persistedState: CommittedState? = null
        this@applyEventWithResult.updateAndGet { oldState ->
            val (nextState, value) = try {
                scheduledEvent.event.applyWithResultTo(oldState)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                applicationScope.launch { reportError(oldState, scheduledEvent.event) }
                throw t
            }
            result = value
            persistedState = CommittedState(oldState, scheduledEvent.event, nextState)
            scheduledEvent.continuation.context.ensureActive() // Don't apply the change if the request got canceled.
            nextState
        }
        persistedState?.let(onCommittedState)

        result as T
    }
    // Launch to make sure we keep the important Event loop running.
    applicationScope.launch { scheduledEvent.continuation.resumeWith(result) }
    return result
}

data class CommittedState(val old: ServerState, val event: WorkshopEvent, val new: ServerState)

internal data class InProgressScheduling(val stateWithoutEventScheduled: ServerState, val event: TimedEventType)
internal fun InProgressScheduling.after(delay: Duration): ServerState = stateWithoutEventScheduled.copy(
    scheduledEvents = stateWithoutEventScheduled.scheduledEvents + TimedEvent(Clock.System.now() + delay, event)
)
internal fun ServerState.scheduling(event: TimedEventType): InProgressScheduling = InProgressScheduling(this, event)

internal val secondDiscoGamePressTimeout = 2.5.seconds
private val danceFloorChangeInterval = 0.8.seconds
private val firstDiscoGamePrivateTickTimeout = 1.5.seconds
private val firstDiscoGamePublicTickTimeout = 10.seconds

private fun ServerState.after(event: TimedEventType): ServerState = when (event) {
    is PressiveGameTickEvent -> when (val state = pressiveGameState) {
        is PressiveGameState.FirstGameDone,
        is PressiveGameState.FirstGameInProgress,
        is PressiveGameState.NotStarted,
        is PressiveGameState.SecondGameDone,
        is PressiveGameState.SecondGameInProgress,
        is PressiveGameState.ThirdGameDone -> this
        is PressiveGameState.ThirdGameInProgress -> copy(
            pressiveGameState = state.copy(
                participantThatIsBeingRung = when (val current = state.participantThatIsBeingRung) {
                    null -> state.order.firstOrNull()
                    else -> state.order.getOrNull(state.order.indexOfFirst { it == current } + 1)
                }
            )
        ).scheduling(PressiveGameTickEvent).after(delayForNextEvent(state))
    }
    is TimedEventType.SecondDiscoGameBackgroundTickEvent -> Random(event.randomSeed).let { random ->
        when (val state = discoGameState) {
            is DiscoGameState.Second.Done,
            is DiscoGameState.First,
            is DiscoGameState.NotStarted -> this
            is DiscoGameState.Second.InProgress -> copy(
                discoGameState = state.copy(
                    orderedParticipants = state
                        .orderedParticipants
                        .map { it.copy(color = discoColors.random(random)) }
                ),
            ).scheduling(TimedEventType.SecondDiscoGameBackgroundTickEvent(random.nextLong())).after(danceFloorChangeInterval)
        }
    }
    is TimedEventType.SecondDiscoGamePressTimeoutEvent -> with(Random(event.randomSeed)) {
        when (val state = discoGameState) {
            is DiscoGameState.Second.Done,
            is DiscoGameState.First,
            is DiscoGameState.NotStarted -> this@after
            is DiscoGameState.Second.InProgress -> copy(discoGameState = state.restartingInstructions())
                .scheduling(TimedEventType.SecondDiscoGamePressTimeoutEvent(nextLong())).after(secondDiscoGamePressTimeout)
        }
    }
    is TimedEventType.FirstDiscoGamePrivateTickEvent -> with(Random(event.randomSeed)) {
        when (val state = discoGameState) {
            is DiscoGameState.First.Done,
            is DiscoGameState.Second,
            is DiscoGameState.NotStarted -> this@after
            is DiscoGameState.First.InProgress -> copy(discoGameState = state.privateTick())
                .scheduling(TimedEventType.FirstDiscoGamePrivateTickEvent(nextLong())).after(firstDiscoGamePrivateTickTimeout)
        }
    }
    is TimedEventType.FirstDiscoGameTargetTickEvent -> with(Random(event.randomSeed)) {
        when (val state = discoGameState) {
            is DiscoGameState.First.Done,
            is DiscoGameState.Second,
            is DiscoGameState.NotStarted -> this@after
            is DiscoGameState.First.InProgress -> copy(discoGameState = state.targetTick())
                .scheduling(TimedEventType.FirstDiscoGameTargetTickEvent(nextLong())).after(firstDiscoGamePublicTickTimeout)
        }
    }
    is TimedEventType.PlaySuccessSound -> this.also { GlobalScope.launch { playSuccessSound() } }
    is TimedEventType.PlayIncrementSound -> this.also { GlobalScope.launch { playProgressSound(event.pitch) } }
    is TimedEventType.PlayProgressLossSound -> this.also { GlobalScope.launch { playFailSound() } }
}

context(Random)
private fun DiscoGameState.First.InProgress.targetTick(): DiscoGameState = copy(target = target.nextRandom())

context(Random)
internal fun ColorAndInstructionWithPrevious.nextRandom() = ColorAndInstructionWithPrevious(
    current = randomColorAndInstruction(),
    previous = current,
)

context(Random)
private fun DiscoGameState.First.InProgress.privateTick(): DiscoGameState = copy(
    states = states.mapValues { (_, value) ->
        when (value) {
            is FirstDiscoGameParticipantState.Done -> value
            is FirstDiscoGameParticipantState.InProgress -> value.copy(
                colorAndInstructionState =
                    if (nextDouble() < .25) target
                    else value.colorAndInstructionState.nextRandom()
            )
        }
    },
)

context(Random)
fun randomColorAndInstruction(): ColorAndInstruction = ColorAndInstruction(
    color = discoColors.random(this@Random),
    (DiscoGameInstruction.entries + null).random(this@Random  )
)


private val discoColors = listOf(
    SerializableColor(80, 80, 80),
    SerializableColor(255, 0, 0),
    SerializableColor(0, 255, 0),
    SerializableColor(0, 0, 255),
    SerializableColor(0, 255, 255),
    SerializableColor(255, 0, 255),
    SerializableColor(255, 255, 0),
    SerializableColor(255, 255, 255),
)

private fun delayForNextEvent(lastState: PressiveGameState.ThirdGameInProgress): Duration = when {
    lastState.participantThatIsBeingRung == lastState.order.last() -> 2.seconds // Wait a bit in between cycles
    else -> 300.milliseconds
}

private suspend fun delayUntil(time: Instant) {
    (time - Clock.System.now())
        .takeIf { it.isPositive() }
        ?.also { timeUntilEvent -> delay(timeUntilEvent) }
}

private suspend fun playSuccessSound() {
    AudioSystem.getClip().use { clip ->
        AudioSystem
            .getAudioInputStream((object {})::class.java.getResourceAsStream("/success.wav"))
            .playIn(clip)
    }
}

private suspend fun playFailSound() {
    AudioSystem.getClip().use { clip ->
        AudioSystem
            .getAudioInputStream((object {})::class.java.getResourceAsStream("/fail.wav"))
            .playIn(clip)
    }
}

/** [progress] is a number between 0 and 1 and effects the pitch of the sound */
private suspend fun playProgressSound(progress: Double) {
    AudioSystem.getClip().use { clip ->
        AudioSystem
            .getAudioInputStream((object {})::class.java.getResourceAsStream("/boop.wav"))
            .changePitch(progress)
            .playIn(clip)
    }
}

private suspend fun AudioInputStream.playIn(clip: Clip) {
    clip.open(this)
    clip.start()
    clip.drain()
    delay(3.seconds)
}

private suspend fun AudioInputStream.changePitch(progress: Double): AudioInputStream {
    val originalFormat = format
    val audioBytes = readAsWavByteArray()
    return AudioInputStream(
        ByteArrayInputStream(audioBytes),
        AudioFormat(
            /* sampleRate = */ originalFormat.sampleRate * (0.5 + progress.coerceIn(.0..1.0)).toFloat(),
            /* sampleSizeInBits = */ originalFormat.sampleSizeInBits,
            /* channels = */ originalFormat.channels,
            /* signed = */ true,
            /* bigEndian = */ originalFormat.isBigEndian
        ),
        audioBytes.size.toLong(),
    )
}

private suspend fun AudioInputStream.readAsWavByteArray(): ByteArray {
    val byteArrayOutputStream = ByteArrayOutputStream()
    withContext(Dispatchers.IO) {
        AudioSystem.write(this@readAsWavByteArray, AudioFileFormat.Type.WAVE, byteArrayOutputStream)
    }
    return byteArrayOutputStream.toByteArray()
}