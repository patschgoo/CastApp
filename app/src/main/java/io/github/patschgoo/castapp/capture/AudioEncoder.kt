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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.patschgoo.castapp.stream.AudioConfig
import io.github.patschgoo.castapp.stream.StreamHub

/**
 * Captures what other apps are playing (Android 10+, AudioPlaybackCapture) and/or
 * the phone's microphone, mixes them and encodes the result to AAC.
 *
 * The audio track is driven by the wall clock: when the capture delivers nothing
 * (no app is playing sound) silence is inserted, and when it delivers more than
 * real time allows the extra is dropped. That keeps the TV's audio clock steady,
 * which matters because the TV's player waits for audio before showing video.
 * The microphone is mixed into that timeline as it comes in.
 */
class AudioEncoder(
    private val context: Context,
    projection: MediaProjection?,
    private val hub: StreamHub,
    /** Capture other apps' sound (needs Android 10+). */
    private val captureApps: Boolean = true,
    /** Mix in the microphone at this level (1 = as recorded, read while running), or null for no microphone. */
    private val micGain: (() -> Float)? = null,
    /**
     * Called once when the phone plays sound but none of it reaches the capture for a while
     * (the playing app doesn't allow its sound to be recorded, or the capture went quiet).
     */
    private val onNoAppSound: (() -> Unit)? = null,
    private val onError: (Throwable) -> Unit,
) {
    /** The capture's permission; the encoder thread moves its recording over when it changes. */
    @Volatile private var projection: MediaProjection? = projection
    @Volatile private var record: AudioRecord? = null
    private var mic: AudioRecord? = null
    private var micRate = SAMPLE_RATE
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** The microphone is being mixed in. */
    val micActive: Boolean get() = mic != null

    /** Other apps' sound is being captured. */
    val appsActive: Boolean get() = record != null

    /**
     * Records other apps' sound through [newProjection] from now on (the user switched between the whole
     * screen and one app). The sound track goes on without a gap the TV would notice.
     */
    fun switchProjection(newProjection: MediaProjection) {
        projection = newProjection
    }

    /** Returns false if no sound can be captured at all (no permission, no encoder, ...). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val p = projection
        return try {
            val apps = if (captureApps && Build.VERSION.SDK_INT >= 29 && p != null) playbackRecord(p) else null
            val m = if (micGain != null) microphoneRecord() else null
            if (apps == null && m == null) return false
            record = apps // released by stop() if anything below fails
            mic = m
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES * 2)
            }
            codec = c
            c.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            apps?.startRecording()
            m?.startRecording()
            running = true
            thread = Thread({ loop(apps, if (apps != null) p else null, m, c) }, "audio-encoder").apply { start() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Audio capture unavailable", e)
            stop()
            false
        }
    }

    @RequiresApi(29)
    @SuppressLint("MissingPermission")
    private fun playbackRecord(projection: MediaProjection): AudioRecord? = try {
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
            null
        } else {
            r
        }
    } catch (e: Exception) {
        Log.w(TAG, "Playback capture unavailable", e)
        null
    }

    /** The microphone, mono, at 48 kHz (or 44.1 kHz, which every phone supports, resampled). */
    @SuppressLint("MissingPermission")
    private fun microphoneRecord(): AudioRecord? {
        for (rate in intArrayOf(SAMPLE_RATE, 44_100)) {
            try {
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) continue
                val r = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer * 2, rate * 2 / 10),
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    micRate = rate
                    return r
                }
                r.release()
            } catch (e: Exception) {
                Log.w(TAG, "Microphone at $rate Hz unavailable", e)
            }
        }
        return null
    }

    /**
     * A new recording of other apps' sound through [p], already started; null if Android refuses it.
     * [old] is stopped first (one capture per projection).
     */
    @SuppressLint("NewApi")
    private fun replaceRecord(old: AudioRecord?, p: MediaProjection): AudioRecord? {
        if (old != null) {
            try { old.stop() } catch (_: Exception) {}
            try { old.release() } catch (_: Exception) {}
        }
        record = null
        val fresh = playbackRecord(p) ?: return null
        return try {
            fresh.startRecording()
            record = fresh
            fresh
        } catch (e: Exception) {
            Log.w(TAG, "Playback capture didn't start", e)
            fresh.release()
            null
        }
    }

    private fun loop(firstRecord: AudioRecord?, firstProjection: MediaProjection?, m: AudioRecord?, c: MediaCodec) {
        var r = firstRecord
        var recordFor = firstProjection
        val pending = ByteArray(BYTES_PER_SECOND) // up to 1 s of PCM waiting for the encoder
        var pendingLen = 0
        val readBuf = ByteArray(CHUNK_BYTES)
        val micRead = ShortArray(MIC_READ)
        val micChunk = ShortArray(FRAMES_PER_CHUNK)
        val micFifo = MonoFifo(maxSamples = SAMPLE_RATE / 4, primeSamples = SAMPLE_RATE / 50 + FRAMES_PER_CHUNK)
        val resampler = if (m != null && micRate != SAMPLE_RATE) LinearResampler(micRate, SAMPLE_RATE) else null
        // Without app sound the timeline is all ours: produce frames promptly to keep the voice's delay short.
        val gapFrames = if (r == null) SAMPLE_RATE / 50 else SAMPLE_RATE / 10
        val slackFrames = if (r == null) 0 else SAMPLE_RATE / 40
        val startNs = System.nanoTime()
        val startUs = startNs / 1000
        var framesQueued = 0L
        val info = MediaCodec.BufferInfo()
        var captureBroken = false
        var brokenAtNs = 0L
        var retries = 0
        var micBroken = false
        // "The phone plays sound, but none comes through": checked about once a second.
        val audioManager = context.getSystemService(android.media.AudioManager::class.java)
        var lastSoundNs = startNs
        var nextQuietCheckNs = startNs + 1_000_000_000L
        var quietReported = false
        try {
            while (running) {
                // A new permission (whole screen <-> one app): record through it from now on.
                val wanted = projection
                if (captureApps && Build.VERSION.SDK_INT >= 29 && wanted != null && recordFor != null && wanted !== recordFor) {
                    recordFor = wanted
                    r = replaceRecord(r, wanted)
                    // Android may need a moment after the switch: then the retries below pick it up.
                    captureBroken = r == null
                    brokenAtNs = System.nanoTime()
                    retries = 0
                    lastSoundNs = System.nanoTime()
                    Log.i(TAG, if (r != null) "Sound capture moved to the new permission" else "Sound capture unavailable with the new permission")
                }
                // The capture stopped delivering: try a fresh one a few times before settling for silence.
                if (captureBroken && retries < MAX_CAPTURE_RETRIES && System.nanoTime() - brokenAtNs > CAPTURE_RETRY_NS) {
                    retries++
                    val p = recordFor
                    val fresh = if (p != null && Build.VERSION.SDK_INT >= 29) replaceRecord(r, p) else null
                    if (fresh != null) {
                        r = fresh
                        captureBroken = false
                        Log.i(TAG, "Sound capture restarted (attempt $retries)")
                    } else {
                        brokenAtNs = System.nanoTime()
                    }
                }
                var micGot = 0
                if (m != null && !micBroken) {
                    micGot = m.read(micRead, 0, micRead.size, AudioRecord.READ_NON_BLOCKING)
                    if (micGot < 0) {
                        Log.w(TAG, "Microphone read failed: $micGot")
                        micBroken = true
                        micGot = 0
                    } else if (micGot > 0) {
                        if (resampler != null) {
                            val out = resampler.process(micRead, micGot)
                            micFifo.write(out, 0, out.size)
                        } else {
                            micFifo.write(micRead, 0, micGot)
                        }
                        // Keep the voice close to real time.
                        if (micFifo.size > SAMPLE_RATE / 8) micFifo.trimTo(SAMPLE_RATE / 25)
                    }
                }

                val elapsedFrames = (System.nanoTime() - startNs) * SAMPLE_RATE / 1_000_000_000L
                var n = if (r == null || captureBroken) 0 else r.read(readBuf, 0, readBuf.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) {
                    // Keep the audio track alive with silence rather than freezing the stream.
                    Log.w(TAG, "AudioRecord.read failed: $n, continuing with silence")
                    captureBroken = true
                    brokenAtNs = System.nanoTime()
                    n = 0
                }
                if (n > 0 && PcmLevel.hasSound(readBuf, n)) lastSoundNs = System.nanoTime()
                if (r != null && !quietReported && onNoAppSound != null) {
                    val now = System.nanoTime()
                    if (now >= nextQuietCheckNs) {
                        nextQuietCheckNs = now + 1_000_000_000L
                        if (now - lastSoundNs > QUIET_HINT_NS && audioManager?.isMusicActive == true) {
                            quietReported = true
                            Log.w(TAG, "The phone plays sound, but the capture stays silent")
                            onNoAppSound.invoke()
                        }
                    }
                }
                val queuedAndPending = framesQueued + pendingLen / BYTES_PER_FRAME
                if (n > 0) {
                    // Drop captured audio that would put us more than 100 ms ahead of real time.
                    if (queuedAndPending <= elapsedFrames + SAMPLE_RATE / 10) {
                        val take = minOf(n, pending.size - pendingLen)
                        System.arraycopy(readBuf, 0, pending, pendingLen, take)
                        pendingLen += take
                    }
                } else if (elapsedFrames - queuedAndPending > gapFrames) {
                    // Nothing is playing: fill the gap with silence (keep a little slack).
                    val missing = (elapsedFrames - queuedAndPending - slackFrames).coerceAtLeast(0)
                    val bytes = minOf(missing * BYTES_PER_FRAME, (pending.size - pendingLen).toLong()).toInt()
                    java.util.Arrays.fill(pending, pendingLen, pendingLen + bytes, 0)
                    pendingLen += bytes
                }

                // Feed whole AAC frames (1024 samples) to the encoder, with the microphone mixed in.
                while (pendingLen >= CHUNK_BYTES) {
                    val inIndex = c.dequeueInputBuffer(2_000)
                    if (inIndex < 0) break
                    val inBuf = c.getInputBuffer(inIndex) ?: break
                    if (m != null) {
                        val got = micFifo.read(micChunk, FRAMES_PER_CHUNK)
                        PcmMixer.mixMicInto(pending, 0, FRAMES_PER_CHUNK, micChunk, 0, got, micGain?.invoke() ?: 0f)
                    }
                    inBuf.clear()
                    inBuf.put(pending, 0, CHUNK_BYTES)
                    val ptsUs = startUs + framesQueued * 1_000_000L / SAMPLE_RATE
                    c.queueInputBuffer(inIndex, 0, CHUNK_BYTES, ptsUs, 0)
                    framesQueued += FRAMES_PER_CHUNK
                    System.arraycopy(pending, CHUNK_BYTES, pending, 0, pendingLen - CHUNK_BYTES)
                    pendingLen -= CHUNK_BYTES
                }

                drain(c, info)
                if (n == 0 && micGot == 0) Thread.sleep(5)
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
        for (rec in listOf(record, mic)) {
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
        }
        record = null
        mic = null
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
        private const val FRAMES_PER_CHUNK = 1024
        private const val CHUNK_BYTES = FRAMES_PER_CHUNK * BYTES_PER_FRAME
        private const val MIC_READ = 2048
        private const val MAX_CAPTURE_RETRIES = 5
        private const val CAPTURE_RETRY_NS = 2_000_000_000L
        /** How long the phone may play sound that never reaches the capture before the user is told. */
        private const val QUIET_HINT_NS = 10_000_000_000L
    }
}
