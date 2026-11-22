package io.github.patschgoo.castapp.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.patschgoo.castapp.stream.AudioConfig
import io.github.patschgoo.castapp.stream.StreamHub

/**
 * Captures what other apps are playing (Android 10+, AudioPlaybackCapture) and
 * encodes it to AAC.
 *
 * The audio track is driven by the wall clock: when the capture delivers nothing
 * (no app is playing sound) silence is inserted, and when it delivers more than
 * real time allows the extra is dropped. That keeps the TV's audio clock steady,
 * which matters because the TV's player waits for audio before showing video.
 */
@RequiresApi(29)
class AudioEncoder(
    private val context: Context,
    private val projection: MediaProjection,
    private val hub: StreamHub,
    private val onError: (Throwable) -> Unit,
) {
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Returns false if audio capture is not possible (no permission, no encoder, ...). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer * 2, BYTES_PER_SECOND / 4))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return false
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES * 2)
            }
            c.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            r.startRecording()
            record = r
            codec = c
            running = true
            thread = Thread({ loop(r, c) }, "audio-encoder").apply { start() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Audio capture unavailable", e)
            stop()
            false
        }
    }

    private fun loop(r: AudioRecord, c: MediaCodec) {
        val pending = ByteArray(BYTES_PER_SECOND) // up to 1 s of PCM waiting for the encoder
        var pendingLen = 0
        val readBuf = ByteArray(CHUNK_BYTES)
        val startNs = System.nanoTime()
        val startUs = startNs / 1000
        var framesQueued = 0L
        val info = MediaCodec.BufferInfo()
        var captureBroken = false
        try {
            while (running) {
                val elapsedFrames = (System.nanoTime() - startNs) * SAMPLE_RATE / 1_000_000_000L
                var n = if (captureBroken) 0 else r.read(readBuf, 0, readBuf.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) {
                    // Keep the audio track alive with silence rather than freezing the stream.
                    Log.w(TAG, "AudioRecord.read failed: $n, continuing with silence")
                    captureBroken = true
                    n = 0
                }
                val queuedAndPending = framesQueued + pendingLen / BYTES_PER_FRAME
                if (n > 0) {
                    // Drop captured audio that would put us more than 100 ms ahead of real time.
                    if (queuedAndPending <= elapsedFrames + SAMPLE_RATE / 10) {
                        val take = minOf(n, pending.size - pendingLen)
                        System.arraycopy(readBuf, 0, pending, pendingLen, take)
                        pendingLen += take
                    }
                } else if (elapsedFrames - queuedAndPending > SAMPLE_RATE / 10) {
                    // Nothing is playing: fill the gap with silence (keep ~25 ms of slack).
                    val missing = (elapsedFrames - queuedAndPending - SAMPLE_RATE / 40).coerceAtLeast(0)
                    val bytes = minOf(missing * BYTES_PER_FRAME, (pending.size - pendingLen).toLong()).toInt()
                    java.util.Arrays.fill(pending, pendingLen, pendingLen + bytes, 0)
                    pendingLen += bytes
                }

                // Feed whole AAC frames (1024 samples) to the encoder.
                while (pendingLen >= CHUNK_BYTES) {
                    val inIndex = c.dequeueInputBuffer(2_000)
                    if (inIndex < 0) break
                    val inBuf = c.getInputBuffer(inIndex) ?: break
                    inBuf.clear()
                    inBuf.put(pending, 0, CHUNK_BYTES)
                    val ptsUs = startUs + framesQueued * 1_000_000L / SAMPLE_RATE
                    c.queueInputBuffer(inIndex, 0, CHUNK_BYTES, ptsUs, 0)
                    framesQueued += CHUNK_BYTES / BYTES_PER_FRAME
                    System.arraycopy(pending, CHUNK_BYTES, pending, 0, pendingLen - CHUNK_BYTES)
                    pendingLen -= CHUNK_BYTES
                }

                drain(c, info)
                if (n == 0) Thread.sleep(5)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running) onError(e)
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = c.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val csd = c.outputFormat.getByteBuffer("csd-0")
                    if (csd != null && hub.audioConfig == null) {
                        val asc = ByteArray(csd.remaining()).also { csd.get(it) }
                        hub.setAudioConfig(AudioConfig(SAMPLE_RATE, CHANNELS, asc, BITRATE))
                    }
                }
                index >= 0 -> {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (hub.audioConfig == null) hub.setAudioConfig(AudioConfig(SAMPLE_RATE, CHANNELS, bytes, BITRATE))
                        } else {
                            hub.pushAudio(bytes, info.presentationTimeUs)
                        }
                    }
                    c.releaseOutputBuffer(index, false)
                }
                else -> return
            }
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    companion object {
        private const val TAG = "AudioEncoder"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BITRATE = 128_000
        private const val BYTES_PER_FRAME = 2 * CHANNELS
        private const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_FRAME
        private const val CHUNK_BYTES = 1024 * BYTES_PER_FRAME
    }
}
