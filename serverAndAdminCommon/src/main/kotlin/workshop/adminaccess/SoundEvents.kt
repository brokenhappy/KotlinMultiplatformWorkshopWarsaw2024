package workshop.adminaccess

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.*
import kotlin.time.Duration.Companion.seconds

@Serializable
sealed class SoundPlayEvent : WorkshopEvent() {
    @Serializable
    data object Success: SoundPlayEvent()
    @Serializable
    data class Increment(val pitch: Double): SoundPlayEvent()
    @Serializable
    data object ProgressLoss: SoundPlayEvent()
}

suspend fun SoundPlayEvent.play() {
    when (this) {
        is SoundPlayEvent.Success -> playSuccessSound()
        is SoundPlayEvent.Increment -> playProgressSound(pitch)
        is SoundPlayEvent.ProgressLoss -> playFailSound()
    }
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