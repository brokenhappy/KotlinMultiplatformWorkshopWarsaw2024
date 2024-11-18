package kmpworkshop.server

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.*
import kotlin.time.Duration.Companion.seconds

@Serializable
sealed class SoundPlayEvents : WorkshopEvent() {
    @Serializable
    data object Success: SoundPlayEvents()
    @Serializable
    data class Increment(val pitch: Double): SoundPlayEvents()
    @Serializable
    data object ProgressLoss: SoundPlayEvents()
}

fun ServerState.after(event: SoundPlayEvents): ServerState = when (event) {
    is SoundPlayEvents.Success -> this.also { GlobalScope.launch { playSuccessSound() } }
    is SoundPlayEvents.Increment -> this.also { GlobalScope.launch { playProgressSound(event.pitch) } }
    is SoundPlayEvents.ProgressLoss -> this.also { GlobalScope.launch { playFailSound() } }
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