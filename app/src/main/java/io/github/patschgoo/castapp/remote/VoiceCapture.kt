package io.github.patschgoo.castapp.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException
import kotlin.math.sqrt

/** The microphone can't be used (no permission, busy, or the rate isn't available). */
class MicrophoneException(message: String) : IOException(message)

/** 16-bit little-endian mono PCM helpers. */
object Pcm {
    /** Every [factor] samples become their average (a crude low-pass, enough for speech). */
    fun downsample(pcm: ByteArray, length: Int, factor: Int): ByteArray {
        if (factor <= 1) return pcm.copyOf(length)
        val samples = length / 2
        val out = ByteArray(samples / factor * 2)
        var o = 0
        var i = 0
        while (i + factor <= samples) {
            var sum = 0
            for (k in 0 until factor) sum += sample(pcm, i + k)
            val v = sum / factor
            out[o++] = v.toByte()
            out[o++] = (v shr 8).toByte()
            i += factor
        }
        return out
    }

    /** Loudness 0..1 (RMS, a bit boosted so normal speech fills most of the range). */
    fun level(pcm: ByteArray, length: Int): Float {
        val samples = length / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val s = sample(pcm, i).toDouble()
            sum += s * s
        }
        return (sqrt(sum / samples) / 32768.0 * 4).toFloat().coerceIn(0f, 1f)
    }

    private fun sample(pcm: ByteArray, index: Int): Int =
        (pcm[index * 2].toInt() and 0xFF) or (pcm[index * 2 + 1].toInt() shl 8)

    /** Recording rates to try for [target]: the rate itself, then multiples to average down. */
    fun captureRates(target: Int): List<Int> = listOf(1, 2, 3, 6).map { target * it }.filter { it <= 48_000 }
}

/** Records speech from the microphone at the rate a TV wants. */
internal class VoiceCapture(private val context: Context, private val sampleRate: Int) {

    /**
     * Records until [stop] says so, the coroutine is cancelled or [maxMs] passed.
     * [onAudio] gets PCM at [sampleRate]; [onLevel] the loudness for the listening indicator.
     */
    suspend fun record(stop: () -> Boolean, maxMs: Long, onLevel: (Float) -> Unit, onAudio: suspend (ByteArray, Int) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("No microphone permission")
        }
        var record: AudioRecord? = null
        var factor = 1
        for (rate in Pcm.captureRates(sampleRate)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5))
            } catch (_: Exception) {
                null
            }
            if (r?.state == AudioRecord.STATE_INITIALIZED) {
                record = r
                factor = rate / sampleRate
                break
            }
            r?.release()
        }
        val rec = record ?: throw MicrophoneException("No usable recording rate")
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MicrophoneException("The microphone is busy")
            // 100 ms per read.
            val buf = ByteArray(sampleRate * factor / 10 * 2)
            val until = System.currentTimeMillis() + maxMs
            while (!stop() && currentCoroutineContext().isActive && System.currentTimeMillis() < until) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) throw MicrophoneException("Recording failed ($n)")
                if (n == 0) continue
                onLevel(Pcm.level(buf, n))
                val pcm = if (factor == 1) buf else Pcm.downsample(buf, n, factor)
                onAudio(pcm, if (factor == 1) n else pcm.size)
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }
}
