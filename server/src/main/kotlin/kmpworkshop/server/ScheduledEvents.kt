package kmpworkshop.server

import kmpworkshop.common.DiscoGameInstruction
import kmpworkshop.common.SerializableColor
import kmpworkshop.server.TimedEventType.PressiveGameTickEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.*
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

suspend fun performScheduledEvents(serverState: MutableStateFlow<ServerState>, eventBus: ReceiveChannel<ScheduledWorkshopEvent>): Nothing {
    coroutineScope {
        launch {
            for (scheduledEvent in eventBus) {
                try {
                    when (scheduledEvent) {
                        is ScheduledWorkshopEvent.AwaitingResult -> {
                            val result = runCatching {
                                serverState.updateAndGet { it.after(scheduledEvent.event) }
                            }
                            // Launch to make sure we keep the important Event loop running.
                            launch { scheduledEvent.continuation.resumeWith(result) }
                        }
                        is ScheduledWorkshopEvent.IgnoringResult -> {
                            // TODO: If exception happens, let's rewind and report the exception!!
                            serverState.update { it.after(scheduledEvent.event) }
                        }
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                    throw t
                }
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
                } catch (t: Throwable) {
                    t.printStackTrace()
                    throw t
                }
            }
    }
    error("Should not be reached")
}

internal data class InProgressScheduling(val stateWithoutEventScheduled: ServerState, val event: TimedEventType)
internal fun InProgressScheduling.after(delay: Duration): ServerState = stateWithoutEventScheduled.copy(
    scheduledEvents = stateWithoutEventScheduled.scheduledEvents + TimedEvent(Clock.System.now() + delay, event)
)
internal fun ServerState.scheduling(event: TimedEventType): InProgressScheduling = InProgressScheduling(this, event)

internal val secondDiscoGamePressTimeout = 2.5.seconds
private val danceFloorChangeInterval = 0.5.seconds
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
    is TimedEventType.SecondDiscoGameBackgroundTickEvent -> when (val state = discoGameState) {
        is DiscoGameState.Second.Done,
        is DiscoGameState.First,
        is DiscoGameState.NotStarted -> this
        is DiscoGameState.Second.InProgress -> copy(
            discoGameState = state.copy(
                orderedParticipants = state
                    .orderedParticipants
                    .map { it.copy(color = discoColors.random()) }
            ),
        ).scheduling(TimedEventType.SecondDiscoGameBackgroundTickEvent).after(danceFloorChangeInterval)
    }
    is TimedEventType.SecondDiscoGamePressTimeoutEvent -> when (val state = discoGameState) {
        is DiscoGameState.Second.Done,
        is DiscoGameState.First,
        is DiscoGameState.NotStarted -> this
        is DiscoGameState.Second.InProgress -> copy(discoGameState = state.restartingInstructions())
            .scheduling(TimedEventType.SecondDiscoGamePressTimeoutEvent).after(secondDiscoGamePressTimeout)

    }
    is TimedEventType.FirstDiscoGamePrivateTickEvent -> {
        val random = Random(event.randomSeed)
        when (val state = discoGameState) {
            is DiscoGameState.First.Done,
            is DiscoGameState.Second,
            is DiscoGameState.NotStarted -> this
            is DiscoGameState.First.InProgress -> copy(discoGameState = state.privateTick(random))
                .scheduling(TimedEventType.FirstDiscoGamePrivateTickEvent(random.nextLong())).after(firstDiscoGamePrivateTickTimeout)
        }
    }
    is TimedEventType.FirstDiscoGameTargetTickEvent -> {
        val random = Random(event.randomSeed)
        when (val state = discoGameState) {
            is DiscoGameState.First.Done,
            is DiscoGameState.Second,
            is DiscoGameState.NotStarted -> this
            is DiscoGameState.First.InProgress -> copy(discoGameState = state.targetTick(random))
                .scheduling(TimedEventType.FirstDiscoGameTargetTickEvent(random.nextLong())).after(firstDiscoGamePublicTickTimeout)
        }
    }
    is TimedEventType.PlaySuccessSound -> this.also { GlobalScope.launch { playSuccessSound() } }
    is TimedEventType.PlayIncrementSound -> this.also { GlobalScope.launch { playProgressSound(event.pitch) } }
    is TimedEventType.PlayProgressLossSound -> this.also { GlobalScope.launch { playFailSound() } }
}

private fun DiscoGameState.First.InProgress.targetTick(random: Random): DiscoGameState = copy(target = target.nextRandom(random))

internal fun ColorAndInstructionWithPrevious.nextRandom(random: Random) = ColorAndInstructionWithPrevious(
    current = randomColorAndInstruction(random),
    previous = current,
)

private fun DiscoGameState.First.InProgress.privateTick(random: Random): DiscoGameState = copy(
    states = states.mapValues { (_, value) ->
        when (value) {
            is FirstDiscoGameParticipantState.Done -> value
            is FirstDiscoGameParticipantState.InProgress -> value.copy(
                colorAndInstructionState =
                    if (random.nextDouble() < .25) target
                    else value.colorAndInstructionState.nextRandom(random)
            )
        }
    },
)

fun randomColorAndInstruction(random: Random): ColorAndInstruction = ColorAndInstruction(
    color = discoColors.random(random),
    (DiscoGameInstruction.entries + null).random(random)
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
    lastState.participantThatIsBeingRung == lastState.order.last() -> 1.seconds // Wait a bit in between cycles
    else -> 150.milliseconds
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